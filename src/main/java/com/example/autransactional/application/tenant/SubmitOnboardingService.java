package com.example.autransactional.application.tenant;

import com.example.autransactional.application.shared.IdempotencyKeyStore;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraProperties;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
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

    private static final String IDENTIFYING_INFORMATION = "identifying_information";

    private final TenantRepository tenants;
    private final KiraApiClient kira;
    private final AuditTrail audit;
    private final ObjectMapper objectMapper;
    private final IdempotencyKeyStore idempotencyKeys;
    private final String bank;

    public SubmitOnboardingService(TenantRepository tenants, KiraApiClient kira, AuditTrail audit,
                                   ObjectMapper objectMapper,
                                   IdempotencyKeyStore idempotencyKeys, KiraProperties properties) {
        this.tenants = tenants;
        this.kira = kira;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.idempotencyKeys = idempotencyKeys;
        this.bank = properties.bank();
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
        // La elegibilidad del producto depende del banco declarado.
        body.put("capabilities", Map.of("requested_banks", List.of(bank)));
        // Amarra el registro de Kira con el id interno, para reconciliar sin adivinar.
        body.put("external_id", tenant.getId().value());

        // La clave se persiste ANTES de la llamada: un timeout no puede crear dos empresas.
        IdempotencyKey key = tenant.reserveOnboardingKey();
        // En transaccion propia: si Kira falla, el rollback de este metodo no puede borrar la clave.
        idempotencyKeys.persistNow(tenant);

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
     * Kira solo escribe los campos que viajan, pero se reenvia la fusion de lo ya enviado con
     * lo nuevo: el payload guardado es la unica copia de los campos que Kira no devuelve.
     */
    @Transactional
    public OnboardingView completeProfile(AuthenticatedOperator operator,
                                          OnboardingCommands.CompleteProfile command) {
        assertCanManage(operator);
        Tenant tenant = load(operator.tenantId());
        tenant.assertActive();
        tenant.assertRegisteredInKira();

        Map<String, Object> body = forUpdate(merge(tenant.getOnboardingPayload(), command.profile()));

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

    /**
     * Adjunta un registro de identifying_information[] con sus archivos.
     *
     * Kira no tiene endpoint de subida: el documento viaja dentro del PUT /v1/users. Los
     * archivos se mandan en base64 y NO se guardan aqui — se limpian del payload antes de
     * persistirlo, porque reenviarlos en cada PUT posterior reventaria el tope de 10 MB.
     * Reenviar la entrada sin archivos no los borra en Kira.
     */
    @Transactional
    public OnboardingView attachDocuments(AuthenticatedOperator operator,
                                          KybDocumentCommands.AttachDocuments command) {
        assertCanManage(operator);
        Tenant tenant = load(operator.tenantId());
        tenant.assertActive();
        tenant.assertRegisteredInKira();

        Map<String, Object> entry = KybDocuments.toIdentifyingInformation(command);
        Map<String, Object> stored = readPayload(tenant.getOnboardingPayload());
        List<Map<String, Object>> merged = KybDocuments.merge(stored.get(IDENTIFYING_INFORMATION), entry);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(IDENTIFYING_INFORMATION, merged);

        try {
            KiraUserState state = KiraUserState.from(kira.updateUser(tenant.getKiraUserId(), body));

            // Se guarda el registro, nunca el archivo.
            stored.put(IDENTIFYING_INFORMATION, KybDocuments.withoutFiles(merged));
            tenant.recordOnboardingPayload(objectMapper.writeValueAsString(stored));
            tenant.applyRemoteState(state.status(), state.missingFields(), state.eligibleProducts(),
                    state.verificationTriggered());
            tenants.save(tenant);

            audit.record(operator, "tenant.kyb_documents_attached", "tenant", tenant.getId().value(),
                    null, "OK", "registro=" + entry.get("type") + " archivos=" + command.documents().size());
        } catch (RuntimeException e) {
            audit.record(operator, "tenant.kyb_documents_attached", "tenant", tenant.getId().value(),
                    null, "ERROR", e.getMessage());
            throw e;
        }

        return refreshInternal(operator, tenant);
    }

    /** Paso 3: el recurso es la autoridad. Tambien cubre el hueco de un webhook perdido. */
    @Transactional
    public OnboardingView refresh(AuthenticatedOperator operator) {
        Tenant tenant = load(operator.tenantId());
        tenant.assertRegisteredInKira();
        return refreshInternal(operator, tenant);
    }

    /**
     * Lo mismo que refresh, sin operador: lo usa el worker de reconciliacion. Recupera un
     * user.status_changed que no llego (Kira reintenta ~80 min y despues lo da por perdido).
     * Devuelve true si el estado cambio.
     */
    @Transactional
    public boolean reconcile(TenantId tenantId) {
        Tenant tenant = load(tenantId);
        if (!tenant.isRegisteredInKira()) {
            return false;
        }
        var antes = tenant.getStatus();
        boolean listaAntes = tenant.isReadyFor(EligibleProduct.USA_VIRTUAL_ACCOUNTS);
        refreshInternal(null, tenant);
        return antes != tenant.getStatus()
                || listaAntes != tenant.isReadyFor(EligibleProduct.USA_VIRTUAL_ACCOUNTS);
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
        Map<String, Object> body = readPayload(storedPayload);
        body.putAll(incoming);
        return body;
    }

    /**
     * Cuerpo valido para PUT /v1/users/{id}.
     *
     * El alta y la actualizacion no comparten nombres, y el PUT rechaza entero con
     * 400 "Unrecognized key(s)" cualquier clave que no reconoce (verificado en sandbox el
     * 15-sep con juriscop). El portal usa los nombres del alta, asi que se traducen aqui:
     *  - type y external_id solo existen en el alta;
     *  - representative_date_of_birth pasa a representative_birth_date;
     *  - business_trade_name pasa a doing_business_as;
     *  - registered_address {} pasa a los address_* planos (Kira lo devuelve anidado igual);
     *  - has_material_intermediary_ownership no tiene equivalente en el PUT y se descarta.
     */
    static Map<String, Object> forUpdate(Map<String, Object> profile) {
        Map<String, Object> body = new LinkedHashMap<>(profile);
        body.remove("type");
        body.remove("external_id");
        if (body.remove("has_material_intermediary_ownership") != null) {
            log.info("has_material_intermediary_ownership solo se acepta en el alta; no viaja en el PUT.");
        }
        rename(body, "representative_date_of_birth", "representative_birth_date");
        rename(body, "business_trade_name", "doing_business_as");

        if (body.remove("registered_address") instanceof Map<?, ?> address) {
            String street = joinNonBlank(address.get("street_line_1"), address.get("street_line_2"));
            putIfText(body, "address_street", street);
            putIfText(body, "address_city", address.get("city"));
            putIfText(body, "address_state", address.get("subdivision"));
            putIfText(body, "address_zip_code", address.get("postal_code"));
            putIfText(body, "address_country", address.get("country"));
        }
        return body;
    }

    private static void rename(Map<String, Object> body, String from, String to) {
        if (body.containsKey(from)) {
            // El portal siempre manda el nombre del alta; el del PUT solo existe en lo ya guardado,
            // asi que el del alta es el valor mas reciente y gana.
            body.put(to, body.remove(from));
        }
    }

    private static String joinNonBlank(Object first, Object second) {
        String a = first == null ? "" : first.toString().trim();
        String b = second == null ? "" : second.toString().trim();
        if (a.isEmpty()) {
            return b;
        }
        return b.isEmpty() ? a : a + ", " + b;
    }

    private static void putIfText(Map<String, Object> body, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            body.put(key, value.toString().trim());
        }
    }

    private Map<String, Object> readPayload(String storedPayload) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (storedPayload != null && !storedPayload.isBlank()) {
            try {
                body.putAll(objectMapper.readValue(storedPayload, PAYLOAD));
            } catch (RuntimeException e) {
                log.warn("El payload de onboarding guardado no es legible; se reenvia solo lo nuevo.");
            }
        }
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
