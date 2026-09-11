package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Onboarding KYB de la empresa cliente contra /v1/users.
 *
 * El flujo no es una linea recta sino un bucle: alta minima, y despues PUT completo + GET
 * hasta que no falte nada para el producto objetivo. Este servicio implementa los tres
 * pasos por separado para que el portal pueda repetir el del medio tantas veces como haga
 * falta sin volver a crear nada.
 */
@Service
public class SubmitOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(SubmitOnboardingService.class);

    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {
    };

    private final TenantRepository tenants;
    private final KiraApiClient kira;
    private final AuditTrail audit;
    private final ObjectMapper objectMapper;

    public SubmitOnboardingService(TenantRepository tenants, KiraApiClient kira, AuditTrail audit,
                                   ObjectMapper objectMapper) {
        this.tenants = tenants;
        this.kira = kira;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OnboardingView status(AuthenticatedOperator operator) {
        return OnboardingView.from(load(operator.tenantId()));
    }

    /**
     * Paso 1: alta minima en Kira. Idempotente por dos vias: si la empresa ya tiene
     * kiraUserId no se vuelve a llamar, y si la llamada se corta a medias el reintento
     * reutiliza la misma clave de idempotencia ya persistida.
     */
    @Transactional
    public OnboardingView register(AuthenticatedOperator operator,
                                   OnboardingCommands.RegisterBusiness command) {
        assertCanManage(operator);
        Tenant tenant = load(operator.tenantId());
        tenant.assertActive();

        if (tenant.isRegisteredInKira()) {
            // No es un error: el portal puede reenviar el formulario. Se devuelve lo que hay.
            return OnboardingView.from(tenant);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // Solo empresas: un user individual se rechaza con 400 business_only.
        body.put("type", "business");
        body.put("business_legal_name", command.businessLegalName());
        body.put("email", command.email());
        body.put("source_of_funds", command.sourceOfFunds());
        // Amarra el registro de Kira con el id interno, para reconciliar sin adivinar.
        body.put("external_id", tenant.getId().value());

        // La clave se persiste ANTES de la llamada: un timeout no puede crear dos empresas.
        IdempotencyKey key = tenant.reserveOnboardingKey();
        tenants.save(tenant);

        try {
            KiraUserState state = KiraUserState.from(kira.createUser(body, key));
            tenant.linkKiraUser(state.kiraUserId());
            tenant.recordOnboardingPayload(objectMapper.writeValueAsString(body));
            tenant.applyRemoteState(state.status(), state.missingFields(), state.eligibleProducts(),
                    state.verificationTriggered());
            tenants.save(tenant);

            audit.record(operator, "tenant.onboarding_registered", "tenant", tenant.getId().value(),
                    key.value(), "OK", "kira_user_id=" + state.kiraUserId());
            return OnboardingView.from(tenant);

        } catch (RuntimeException e) {
            log.error("Fallo el alta de la empresa {} en Kira: {}", tenant.getId(), e.getMessage());
            audit.record(operator, "tenant.onboarding_registered", "tenant", tenant.getId().value(),
                    key.value(), "ERROR", e.getMessage());
            throw e;
        }
    }

    /**
     * Paso 2: completa el perfil. Se repite tantas veces como haga falta.
     *
     * Kira exige el objeto COMPLETO en cada PUT: lo que no viaje se borra en silencio. Por
     * eso se envia la fusion de lo ya enviado con lo nuevo, y no solo los campos del
     * formulario que el usuario acaba de tocar.
     */
    @Transactional
    public OnboardingView completeProfile(AuthenticatedOperator operator,
                                          OnboardingCommands.CompleteProfile command) {
        assertCanManage(operator);
        Tenant tenant = load(operator.tenantId());
        tenant.assertActive();
        tenant.assertRegisteredInKira();

        Map<String, Object> body = merge(tenant.getOnboardingPayload(), command.profile());

        KiraUserState state = KiraUserState.from(kira.updateUser(tenant.getKiraUserId(), body));
        tenant.recordOnboardingPayload(objectMapper.writeValueAsString(body));
        tenant.applyRemoteState(state.status(), state.missingFields(), state.eligibleProducts(),
                state.verificationTriggered());
        tenants.save(tenant);

        audit.record(operator, "tenant.onboarding_profile_updated", "tenant", tenant.getId().value(),
                null, "OK", "campos=" + command.profile().keySet());

        // El PUT devuelve missing_fields, pero el estado real solo lo confirma el GET.
        return refreshInternal(operator, tenant);
    }

    /** Paso 3: el recurso es la autoridad. Tambien cubre el hueco de un webhook perdido. */
    @Transactional
    public OnboardingView refresh(AuthenticatedOperator operator) {
        Tenant tenant = load(operator.tenantId());
        tenant.assertRegisteredInKira();
        return refreshInternal(operator, tenant);
    }

    private OnboardingView refreshInternal(AuthenticatedOperator operator, Tenant tenant) {
        JsonNode response = kira.getUser(tenant.getKiraUserId());
        KiraUserState state = KiraUserState.from(response);
        tenant.applyRemoteState(state.status(), state.missingFields(), state.eligibleProducts(),
                state.verificationTriggered());
        tenants.save(tenant);
        return OnboardingView.from(tenant);
    }

    /**
     * Fusion superficial: una clave presente en lo nuevo reemplaza entera a la guardada.
     * Es justo lo que Kira necesita para los arrays (associated_persons debe viajar
     * completo o pierde campos), y evita inventar una semantica de mezcla profunda que
     * la API no tiene.
     */
    private Map<String, Object> merge(String storedPayload, Map<String, Object> incoming) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (storedPayload != null && !storedPayload.isBlank()) {
            try {
                body.putAll(objectMapper.readValue(storedPayload, PAYLOAD));
            } catch (RuntimeException e) {
                log.warn("El payload de onboarding guardado no es legible; se reenvia solo lo nuevo.");
            }
        }
        body.putAll(incoming);
        return body;
    }

    private void assertCanManage(AuthenticatedOperator operator) {
        if (!operator.role().canManageCompliance()) {
            throw new DomainException("Tu rol no puede gestionar el onboarding de la empresa.");
        }
    }

    private Tenant load(TenantId tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
    }
}
