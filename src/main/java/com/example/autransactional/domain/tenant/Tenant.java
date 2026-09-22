package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.TenantId;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agregado Tenant: la empresa cliente que opera sobre el BFF
 * (p. ej. Juriscop, Bankvision, AU Colombia).
 *
 * Es la contraparte local del "user" de Kira: kiraUserId guarda el id que devuelve
 * POST /v1/users y es lo que ata todo el KYB, las cuentas virtuales y los pagos.
 *
 * El onboarding NO es una linea recta sino un bucle: se crea el user con lo minimo, y
 * despues se repite PUT + GET hasta que missingFields queda vacio para el producto
 * objetivo. Por eso el agregado guarda el estado de ese bucle y no solo el resultado.
 */
@Getter
public class Tenant {

    private final TenantId id;
    private final String name;
    private Instant createdAt;

    private String taxId;
    private String jurisdiction;
    private String kiraUserId;
    private TenantStatus status;
    private List<EligibleProduct> eligibleProducts;
    private Instant updatedAt;

    // --- Estado del bucle de onboarding (propio del BFF, ver notas en cada campo) ---

    private MissingFields missingFields;
    private boolean verificationTriggered;
    private String onboardingPayload;
    private String onboardingIdempotencyKey;
    private String rejectionReason;

    /**
     * Borrador del formulario de vinculacion: lo que el portal va rellenando antes de enviarlo
     * a Kira. Kira no tiene borradores (la verificacion arranca sola con el expediente completo),
     * asi que se guarda aqui y nunca viaja a Kira por si solo. No contiene archivos.
     */
    private String onboardingDraft;
    private Instant onboardingDraftUpdatedAt;

    /** Parametrizacion propia del portal (arquitectura §8): no viene de Kira. */
    private TenantSettings settings = TenantSettings.defaults();

    public Tenant(TenantId id, String name, String taxId, String jurisdiction) {
        if (name == null || name.isBlank()) {
            throw new DomainException("La empresa cliente necesita un nombre.");
        }
        this.id = id;
        this.name = name;
        this.taxId = taxId;
        this.jurisdiction = jurisdiction == null || jurisdiction.isBlank() ? "Colombia" : jurisdiction;
        this.status = TenantStatus.CREATED;
        this.eligibleProducts = List.of();
        this.missingFields = MissingFields.empty();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Tenant rehydrate(TenantId id, String name, String taxId, String jurisdiction,
                                   String kiraUserId, TenantStatus status,
                                   List<EligibleProduct> eligibleProducts, MissingFields missingFields,
                                   boolean verificationTriggered, String onboardingPayload,
                                   String onboardingIdempotencyKey, String rejectionReason,
                                   Instant createdAt, Instant updatedAt) {
        Tenant t = new Tenant(id, name, taxId, jurisdiction);
        // Sin esto, cada lectura "reiniciaba" la fecha de alta con la hora actual.
        t.createdAt = createdAt == null ? t.createdAt : createdAt;
        t.kiraUserId = kiraUserId;
        t.status = status == null ? TenantStatus.CREATED : status;
        t.eligibleProducts = eligibleProducts == null ? List.of() : List.copyOf(eligibleProducts);
        t.missingFields = missingFields == null ? MissingFields.empty() : missingFields;
        t.verificationTriggered = verificationTriggered;
        t.onboardingPayload = onboardingPayload;
        t.onboardingIdempotencyKey = onboardingIdempotencyKey;
        t.rejectionReason = rejectionReason;
        t.updatedAt = updatedAt;
        return t;
    }

    // ── Onboarding ──────────────────────────────────────────────────────

    /** Tope del borrador serializado: datos de formulario, nunca documentos. */
    public static final int MAX_DRAFT_CHARS = 64 * 1024;

    /** Reemplaza el borrador completo. El portal es dueno del objeto entero. */
    public void saveOnboardingDraft(String draftJson, Instant now) {
        if (draftJson != null && draftJson.length() > MAX_DRAFT_CHARS) {
            throw new DomainException("El borrador supera el tamano permitido. Los documentos no se guardan "
                    + "en el borrador: se suben al proveedor en su paso.");
        }
        this.onboardingDraft = draftJson;
        this.onboardingDraftUpdatedAt = now;
        touch();
    }

    /** Solo para rehidratar desde la base: no modifica la fecha de actualizacion del agregado. */
    public void restoreOnboardingDraft(String draftJson, Instant updatedAt) {
        this.onboardingDraft = draftJson;
        this.onboardingDraftUpdatedAt = updatedAt;
    }

    /** Rehidrata la parametrizacion desde la base; no falla si nunca se guardo nada. */
    public void restoreSettings(TenantSettings settings) {
        this.settings = settings == null ? TenantSettings.defaults() : settings;
    }

    /** Actualiza la parametrizacion. Solo PLATFORM_OPERATOR puede llegar hasta aqui (ver servicio). */
    public void applySettings(TenantSettings settings) {
        if (settings == null) {
            throw new DomainException("La parametrizacion no puede quedar vacia.");
        }
        this.settings = settings;
        touch();
    }

    /**
     * Reserva la clave de idempotencia del alta en Kira.
     *
     * Una clave por intencion de negocio, no por intento HTTP: se persiste ANTES de la
     * primera llamada y todos los reintentos reutilizan la misma, o un timeout seguido de
     * reintento crearia dos empresas en Kira.
     */
    public IdempotencyKey reserveOnboardingKey() {
        if (onboardingIdempotencyKey == null) {
            onboardingIdempotencyKey = IdempotencyKey.newKey().value();
            touch();
        }
        return IdempotencyKey.of(onboardingIdempotencyKey);
    }

    /**
     * Se invoca tras el 201 de POST /v1/users.
     *
     * No cambia el estado: el 201 devuelve CREATED y la verificacion NO se dispara sola.
     * Solo un PUT completo (con source_of_funds) la dispara.
     */
    public void linkKiraUser(String kiraUserId) {
        if (kiraUserId == null || kiraUserId.isBlank()) {
            throw new DomainException("Kira no devolvio un identificador de usuario.");
        }
        if (this.kiraUserId != null && !this.kiraUserId.equals(kiraUserId)) {
            throw new DomainException("Esta empresa ya esta dada de alta en Kira con otro identificador.");
        }
        this.kiraUserId = kiraUserId;
        touch();
    }

    public boolean isRegisteredInKira() {
        return kiraUserId != null;
    }

    public void assertRegisteredInKira() {
        if (!isRegisteredInKira()) {
            throw new DomainException("La empresa todavia no esta dada de alta en Kira.");
        }
    }

    /**
     * Guarda el objeto completo enviado a Kira.
     *
     * Es obligatorio conservarlo: el GET no devuelve los campos del cuestionario y un PUT
     * parcial borra en silencio lo que no viaje en el, asi que el siguiente PUT solo puede
     * construirse a partir de lo que se envio la vez anterior.
     */
    public void recordOnboardingPayload(String payloadJson) {
        this.onboardingPayload = payloadJson;
        touch();
    }

    /** Asienta lo que devolvieron POST/PUT/GET de /v1/users. */
    public void applyRemoteState(TenantStatus incoming, MissingFields missingFields,
                                 List<EligibleProduct> eligibleProducts, Boolean verificationTriggered) {
        if (incoming != null) {
            this.status = incoming;
        }
        if (missingFields != null) {
            this.missingFields = missingFields;
        }
        if (eligibleProducts != null) {
            this.eligibleProducts = List.copyOf(eligibleProducts);
        }
        // Una vez disparada, la verificacion no se "des-dispara": el GET no reporta el campo.
        if (Boolean.TRUE.equals(verificationTriggered)) {
            this.verificationTriggered = true;
        }
        touch();
    }

    public Optional<EligibleProduct> product(String productCode) {
        return eligibleProducts.stream()
                .filter(p -> p.productCode().equalsIgnoreCase(productCode))
                .findFirst();
    }

    /** Abrir cuenta virtual exige KYB VERIFIED y el producto concreto elegible. */
    public boolean isReadyFor(String productCode) {
        return isVerified()
                && missingFields.isCompleteFor(productCode)
                && product(productCode).map(EligibleProduct::eligible).orElse(false);
    }

    /**
     * POST /v1/users/{id}/liveness-link devuelve 422 "No verification is in progress"
     * si el KYB aun no se disparo. Se comprueba aqui para no gastar la llamada.
     */
    public void assertVerificationInProgress() {
        assertRegisteredInKira();
        if (!verificationTriggered && status == TenantStatus.CREATED) {
            throw new DomainException("La verificacion todavia no se ha disparado: completa los campos "
                    + "pendientes antes de pedir los enlaces de prueba de vida.");
        }
    }

    /**
     * Guarda el motivo del rechazo del KYB.
     *
     * Solo llega por el webhook user.verification.failed y solo una vez: GET /v1/users/{id}
     * nunca lo expone. Si no se captura aqui, el operador ve un REJECTED sin explicacion y
     * no hay forma de recuperarla.
     */
    public void rejectVerification(String reason) {
        this.status = TenantStatus.REJECTED;
        if (reason != null && !reason.isBlank()) {
            this.rejectionReason = reason.length() > 500 ? reason.substring(0, 500) : reason;
        }
        touch();
    }

    // ── Estado general ──────────────────────────────────────────────────

    public boolean isVerified() {
        return status == TenantStatus.VERIFIED;
    }

    public void assertActive() {
        if (!status.canOperate()) {
            throw new DomainException("La organizacion " + name + " esta desactivada (estado " + status + ").");
        }
    }

    /** Ninguna operacion de tesoreria sale hacia Kira si el KYB no esta aprobado. */
    public void assertCanOperateTreasury() {
        assertActive();
        if (!isVerified()) {
            throw new DomainException(
                    "La organizacion " + name + " todavia no supero la verificacion (estado " + status + ").");
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
