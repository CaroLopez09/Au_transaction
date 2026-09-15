package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.PostalAddress;
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
 * El registro local no es una copia por comodidad: Kira no devuelve todos los datos de
 * cada persona, y fusiona associated_persons[] por email, asi que este lado es la unica
 * fuente completa de quienes son y de lo que ya se le envio.
 */
@Service
public class SyncUbosService {

    static final String BIOMETRIC_CONSENT_REQUIRED = "Confirma que las personas consintieron el tratamiento "
            + "de sus datos biometricos antes de la selfie o la prueba de vida.";

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

        assertEmailNotTaken(operator.tenantId(), ubo.getId(), command.email());
        ubo.rename(command.firstName(), command.lastName(), command.roleInCompany());
        ubo.describeEmail(command.email());
        ubo.describeDocument(command.documentType(), command.documentNumber());
        ubo.describeRole(command.hasOwnership(), command.ownershipPercentage(), command.hasControl(),
                command.isSigner(), command.politicallyExposed(), command.countryOfBirth());
        ubo.describeIdentity(command.birthDate(), command.nationality(), command.occupation(), command.gender(),
                command.phoneNumber(), command.documentCountry(),
                command.address() == null ? null : command.address().toDomain());
        ubos.save(ubo);

        audit.record(operator, "tenant.ubo_saved", "ubo", ubo.getId(), null, "OK",
                "propiedad=" + ubo.getOwnershipPercentage() + "%");
        return UboView.from(ubo);
    }

    /**
     * Quita un beneficiario cargado por error.
     *
     * Solo antes de que Kira lo conozca: alli las personas se fusionan por email y quitar una
     * del array no la borra, asi que borrarla aqui dejaria las dos listas descuadradas.
     */
    @Transactional
    public UboView.Roster delete(AuthenticatedOperator operator, String uboId) {
        assertCanManage(operator);
        Ubo ubo = ubos.findByIdAndTenant(uboId, operator.tenantId())
                .orElseThrow(() -> new DomainException("El beneficiario final no existe."));
        if (ubo.isKnownToKira()) {
            throw new DomainException("Este beneficiario ya esta registrado en Kira y no se puede quitar desde "
                    + "el portal. Corrige sus datos o contacta a soporte.");
        }
        ubos.delete(ubo);
        audit.record(operator, "tenant.ubo_deleted", "ubo", ubo.getId(), null, "OK", ubo.fullName());
        return UboView.Roster.from(ubos.rosterOf(operator.tenantId()));
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
        for (Ubo ubo : roster.members()) {
            ubo.markSyncedToKira();
            ubos.save(ubo);
        }

        audit.record(operator, "tenant.ubos_synced", "tenant", operator.tenantId().value(), null, "OK",
                "beneficiarios=" + roster.members().size());
        return view;
    }

    /**
     * Adjunta un documento de identidad a UNA persona.
     *
     * El documento va anidado en la entrada de esa persona dentro de associated_persons[],
     * que Kira empareja por email: de ahi que el beneficiario necesite uno antes de subir
     * nada. La persona viaja con sus datos conocidos para que la fusion no la deje a medias.
     *
     * Mandar la selfie junto al documento le basta a Kira para el face match, sin sesion
     * interactiva: no sustituye al enlace de liveness, pero adelanta esa parte.
     */
    @Transactional
    public UboView attachDocuments(AuthenticatedOperator operator, String uboId,
                                   KybDocumentCommands.AttachDocuments command, boolean biometricConsent) {
        assertCanManage(operator);
        boolean conSelfie = command.documents().stream().anyMatch(d -> "selfie".equalsIgnoreCase(d.documentType()));
        if (conSelfie && !biometricConsent) {
            throw new DomainException(BIOMETRIC_CONSENT_REQUIRED);
        }
        Tenant tenant = load(operator.tenantId());
        tenant.assertRegisteredInKira();

        Ubo ubo = ubos.findByIdAndTenant(uboId, operator.tenantId())
                .orElseThrow(() -> new DomainException("El beneficiario final no existe."));
        ubo.assertIdentifiableInKira();

        Map<String, Object> entry = KybDocuments.toIdentifyingInformation(command);
        Map<String, Object> person = toAssociatedPerson(ubo);
        person.put("identifying_information", List.of(entry));

        try {
            kira.updateUser(tenant.getKiraUserId(), Map.of("associated_persons", List.of(person)));
        } catch (RuntimeException e) {
            audit.record(operator, "tenant.ubo_documents_attached", "ubo", ubo.getId(), null,
                    "ERROR", e.getMessage());
            throw e;
        }

        // Al subir su documento la persona viaja entera: desde aqui Kira ya la conoce.
        ubo.markSyncedToKira();
        ubos.save(ubo);

        if (conSelfie) {
            audit.record(operator, "tenant.biometric_consent_recorded", "ubo", ubo.getId(), null, "OK",
                    "selfie");
        }
        // El archivo no se guarda en ningun sitio: lo custodia Kira.
        audit.record(operator, "tenant.ubo_documents_attached", "ubo", ubo.getId(), null, "OK",
                "registro=" + entry.get("type") + " archivos=" + command.documents().size());
        return UboView.from(ubo);
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
        if (command == null || !Boolean.TRUE.equals(command.biometricConsent())) {
            throw new DomainException(BIOMETRIC_CONSENT_REQUIRED);
        }
        Tenant tenant = load(operator.tenantId());
        // Sin verificacion en curso, Kira responde 422: se corta antes de gastar la llamada.
        tenant.assertVerificationInProgress();

        Map<String, Object> body = new LinkedHashMap<>();
        if (command.successUrl() != null && command.rejectUrl() != null) {
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

        audit.record(operator, "tenant.biometric_consent_recorded", "tenant",
                operator.tenantId().value(), null, "OK", "liveness");
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
        // Kira empareja por email: sin el, cada PUT le crea una persona nueva.
        if (ubo.getEmail() != null) {
            person.put("email", ubo.getEmail());
        }
        person.put("has_ownership", ubo.isHasOwnership());
        person.put("ownership_percentage", ubo.getOwnershipPercentage());
        person.put("has_control", ubo.isHasControl());
        person.put("is_signer", ubo.isSigner());
        person.put("pep_status", ubo.isPoliticallyExposed());
        person.put("country_of_birth", ubo.getCountryOfBirth());
        person.put("title", ubo.getRoleInCompany());
        putIfPresent(person, "document_type", ubo.getDocumentType());
        putIfPresent(person, "document_number", ubo.getDocumentNumber());
        putIfPresent(person, "document_country", ubo.getDocumentCountry());
        putIfPresent(person, "birth_date", ubo.getBirthDate() == null ? null : ubo.getBirthDate().toString());
        putIfPresent(person, "nationality", ubo.getNationality());
        putIfPresent(person, "occupation", ubo.getOccupation());
        putIfPresent(person, "gender", ubo.getGender());
        // En associated_persons el telefono se llama phone_number, no phone.
        putIfPresent(person, "phone_number", ubo.getPhoneNumber());
        PostalAddress address = ubo.getResidentialAddress();
        if (address != null) {
            // El PUT solo acepta la direccion plana; residential_address {} es del alta.
            putIfPresent(person, "address_street", address.streetName());
            putIfPresent(person, "address_city", address.city());
            putIfPresent(person, "address_state", address.state());
            putIfPresent(person, "address_zip_code", address.postalCode());
            putIfPresent(person, "address_country", address.country());
        }
        // person_reference_id no es un campo de entrada (solo sale en enlaces y webhooks).
        return person;
    }

    /** Empareja el enlace con su beneficiario: por referencia de Kira, o por nombre. */
    /**
     * Kira empareja a las personas por email: dos beneficiarios con el mismo correo son, para
     * Kira, la misma persona, y localmente suman su participacion dos veces.
     */
    private void assertEmailNotTaken(TenantId tenantId, String uboId, String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        boolean taken = ubos.findByTenant(tenantId).stream()
                .anyMatch(other -> !other.getId().equals(uboId) && email.trim().equalsIgnoreCase(other.getEmail()));
        if (taken) {
            throw new DomainException("Ya hay un beneficiario con ese correo. El proveedor identifica a cada persona "
                    + "por su correo: edita el existente en lugar de crear otro.");
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

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
