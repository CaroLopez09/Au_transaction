package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.Ubo;
import com.example.autransactional.domain.tenant.UboRepository;
import com.example.autransactional.domain.tenant.UboRoster;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Beneficiarios finales: registro local, sincronizacion con Kira y enlaces de prueba de vida.
 *
 * El registro local no es una copia por comodidad. Kira exige que 'associated_persons'
 * viaje COMPLETO en cada PUT —lo que no va, se borra en silencio— asi que la unica forma
 * de reconstruir el array es tenerlo entero de este lado.
 */
@Service
public class SyncUbosService {

    private static final Logger log = LoggerFactory.getLogger(SyncUbosService.class);

    private final UboRepository ubos;
    private final TenantRepository tenants;
    private final SubmitOnboardingService onboarding;
    private final KiraApiClient kira;
    private final AuditTrail audit;

    public SyncUbosService(UboRepository ubos, TenantRepository tenants,
                           SubmitOnboardingService onboarding, KiraApiClient kira, AuditTrail audit) {
        this.ubos = ubos;
        this.tenants = tenants;
        this.onboarding = onboarding;
        this.kira = kira;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public UboView.Roster list(AuthenticatedOperator operator) {
        return UboView.Roster.from(ubos.rosterOf(operator.tenantId()));
    }

    /** Alta o edicion local. No toca Kira: eso lo hace {@link #syncToKira}. */
    @Transactional
    public UboView save(AuthenticatedOperator operator, UboCommands.SaveUbo command) {
        assertCanManage(operator);

        Ubo ubo = command.id() == null || command.id().isBlank()
                ? new Ubo(UUID.randomUUID().toString(), operator.tenantId(),
                command.firstName(), command.lastName(), command.ownershipPercentage(),
                command.roleInCompany())
                : ubos.findByIdAndTenant(command.id(), operator.tenantId())
                .orElseThrow(() -> new DomainException("El beneficiario final no existe."));

        ubo.describeDocument(command.documentType(), command.documentNumber());
        ubo.describeRole(command.hasOwnership(), command.ownershipPercentage(), command.hasControl(),
                command.isSigner(), command.politicallyExposed(), command.countryOfBirth());
        ubos.save(ubo);

        audit.record(operator, "tenant.ubo_saved", "ubo", ubo.getId(), null, "OK",
                "propiedad=" + ubo.getOwnershipPercentage() + "%");
        return UboView.from(ubo);
    }

    /**
     * Envia el array completo de beneficiarios a Kira.
     *
     * Se valida el grupo antes de llamar: sin una persona con propiedad >= 5 %, Kira acepta
     * el PUT y deja el KYB atascado pidiendo "associated_persons:beneficial_owner". Fallar
     * aqui es mas barato que descubrirlo tres pantallas mas adelante.
     */
    @Transactional
    public OnboardingView syncToKira(AuthenticatedOperator operator) {
        assertCanManage(operator);

        UboRoster roster = ubos.rosterOf(operator.tenantId());
        roster.assertReadyForVerification();

        List<Map<String, Object>> associatedPersons = new ArrayList<>();
        for (Ubo ubo : roster.members()) {
            associatedPersons.add(toAssociatedPerson(ubo));
        }

        OnboardingView view = onboarding.completeProfile(operator,
                new OnboardingCommands.CompleteProfile(Map.of("associated_persons", associatedPersons)));

        audit.record(operator, "tenant.ubos_synced", "tenant", operator.tenantId().value(), null, "OK",
                "beneficiarios=" + roster.members().size());
        return view;
    }

    /**
     * Pide un enlace de prueba de vida por beneficiario final.
     *
     * Llamadas repetidas devuelven el mismo enlace salvo que cambien las URLs de redireccion,
     * asi que reintentar es seguro. El enlace vive 7 dias y el resultado real NO llega por la
     * landing de redireccion, sino por el webhook user.liveness_completed.
     */
    @Transactional
    public UboView.Roster requestLivenessLinks(AuthenticatedOperator operator,
                                               UboCommands.RequestLivenessLinks command) {
        assertCanManage(operator);
        Tenant tenant = load(operator.tenantId());
        // Sin verificacion en curso, Kira responde 422: se corta antes de gastar la llamada.
        tenant.assertVerificationInProgress();

        Map<String, Object> body = new LinkedHashMap<>();
        if (command != null && command.successUrl() != null && command.rejectUrl() != null) {
            body.put("redirect", Map.of(
                    "success_url", command.successUrl(),
                    "reject_url", command.rejectUrl()));
        }

        JsonNode response = kira.requestLivenessLink(tenant.getKiraUserId(), body);
        JsonNode payload = response.has("data") ? response.get("data") : response;
        JsonNode links = payload.path("links");

        int asignados = 0;
        for (JsonNode link : links) {
            if (applyLink(operator.tenantId(), link)) {
                asignados++;
            }
        }

        audit.record(operator, "tenant.liveness_links_requested", "tenant",
                operator.tenantId().value(), null, "OK", "enlaces=" + asignados);
        return list(operator);
    }

    /**
     * Asienta el resultado del webhook user.liveness_completed.
     *
     * Es la unica fuente de verdad del resultado y llega una sola vez, sin reintentos:
     * si no se proyecta aqui, el dato no se recupera por GET.
     */
    @Transactional
    public void applyLivenessResult(String personReferenceId,
                                    com.example.autransactional.domain.tenant.LivenessStatus status) {
        if (personReferenceId == null || personReferenceId.isBlank()) {
            log.warn("Evento de liveness sin person_reference_id: no se puede atribuir a un beneficiario.");
            return;
        }
        Optional<Ubo> found = ubos.findByPersonReferenceId(personReferenceId);
        if (found.isEmpty()) {
            log.info("Evento de liveness para la persona {} sin correspondencia local.", personReferenceId);
            return;
        }
        Ubo ubo = found.get();
        ubo.applyLivenessStatus(status);
        ubos.save(ubo);
    }

    /**
     * Construye una entrada de associated_persons[].
     *
     * document_type se envia siempre: si se omite, Kira asume national_id y a partir de ahi
     * exige el reverso del documento.
     */
    private Map<String, Object> toAssociatedPerson(Ubo ubo) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("first_name", ubo.getFirstName());
        person.put("last_name", ubo.getLastName());
        person.put("has_ownership", ubo.isHasOwnership());
        person.put("ownership_percentage", ubo.getOwnershipPercentage());
        person.put("has_control", ubo.isHasControl());
        person.put("is_signer", ubo.isSigner());
        person.put("pep_status", ubo.isPoliticallyExposed());
        person.put("country_of_birth", ubo.getCountryOfBirth());
        person.put("title", ubo.getRoleInCompany());
        if (ubo.getDocumentType() != null) {
            person.put("document_type", ubo.getDocumentType());
        }
        if (ubo.getDocumentNumber() != null) {
            person.put("document_number", ubo.getDocumentNumber());
        }
        if (ubo.getPersonReferenceId() != null) {
            person.put("person_reference_id", ubo.getPersonReferenceId());
        }
        return person;
    }

    /** Empareja el enlace con su beneficiario: por referencia de Kira, o por nombre. */
    private boolean applyLink(TenantId tenantId, JsonNode link) {
        String reference = text(link, "person_reference_id");
        String name = text(link, "name");
        String url = text(link, "liveness_link");
        String expires = text(link, "expires_at");

        Ubo target = null;
        if (reference != null) {
            target = ubos.findByPersonReferenceId(reference).orElse(null);
        }
        if (target == null && name != null) {
            target = ubos.rosterOf(tenantId).members().stream()
                    .filter(u -> name.equalsIgnoreCase(u.fullName()))
                    .findFirst()
                    .orElse(null);
        }
        if (target == null) {
            log.warn("Enlace de liveness para '{}' sin beneficiario local que lo reciba.", name);
            return false;
        }

        if (reference != null) {
            target.linkKiraPerson(reference);
        }
        target.assignLivenessLink(url, parseInstant(expires));
        ubos.save(target);
        return true;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            // Sin fecha explicita, la vigencia documentada del enlace es de 7 dias.
            return Instant.now().plus(java.time.Duration.ofDays(7));
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return Instant.now().plus(java.time.Duration.ofDays(7));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void assertCanManage(AuthenticatedOperator operator) {
        if (!operator.role().canManageCompliance()) {
            throw new DomainException("Tu rol no puede gestionar los beneficiarios finales.");
        }
    }

    private Tenant load(TenantId tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
    }
}
