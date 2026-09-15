package com.example.autransactional.application.webhook;

import com.example.autransactional.application.account.KiraDepositEvent;
import com.example.autransactional.application.account.RecordDepositService;
import com.example.autransactional.application.compliance.AnswerRfiService;
import com.example.autransactional.application.notification.NotificationService;
import com.example.autransactional.application.tenant.SyncUbosService;
import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.account.VirtualAccountStatus;
import com.example.autransactional.domain.tenant.LivenessStatus;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.domain.treasury.PayoutStatus;
import com.example.autransactional.infrastructure.config.AsyncConfig;
import com.example.autransactional.infrastructure.observability.IntegrationMetrics;
import com.example.autransactional.infrastructure.persistence.WebhookEventEntity;
import com.example.autransactional.infrastructure.persistence.WebhookEventJpaRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Procesamiento asincrono de los eventos de Kira.
 *
 * Dos pasos: record() guarda el evento dentro de la peticion de Kira (si falla, 5xx y Kira
 * reintenta) y projectLater() lo proyecta despues de responder. La idempotencia se apoya en la
 * unicidad de data.event_id en base de datos.
 */
@Service
public class ProcessWebhookUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessWebhookUseCase.class);

    private final WebhookEventJpaRepository events;
    private final PayoutRepository payouts;
    private final TenantRepository tenants;
    private final VirtualAccountRepository accounts;
    private final RecordDepositService deposits;
    private final SyncUbosService ubos;
    private final AnswerRfiService rfis;
    private final ObjectMapper objectMapper;
    private final NotificationService notifications;

    /**
     * El propio bean a traves del proxy de Spring: projectLater corre en otro hilo y necesita que
     * reproject abra su transaccion, cosa que una llamada directa a this se saltaria.
     */
    @Autowired
    @Lazy
    private ProcessWebhookUseCase self;

    /** Opcional para que las pruebas que construyen el caso de uso a mano no lo necesiten. */
    @Autowired(required = false)
    private IntegrationMetrics metrics;

    public ProcessWebhookUseCase(WebhookEventJpaRepository events, PayoutRepository payouts,
                                 TenantRepository tenants, VirtualAccountRepository accounts,
                                 RecordDepositService deposits, SyncUbosService ubos,
                                 AnswerRfiService rfis, ObjectMapper objectMapper,
                                 NotificationService notifications) {
        this.events = events;
        this.payouts = payouts;
        this.tenants = tenants;
        this.accounts = accounts;
        this.deposits = deposits;
        this.ubos = ubos;
        this.rfis = rfis;
        this.objectMapper = objectMapper;
        this.notifications = notifications;
    }

    /**
     * Paso 1, dentro de la peticion de Kira: guarda el evento y confirma la escritura.
     *
     * Se hace ANTES de responder 2xx (webhooks/best-practices: "write the event to your own queue
     * or table, answer 2xx, process from there"). Si la base falla, la excepcion llega al
     * controlador como 5xx y Kira reintenta (1, 5, 15 y 60 min). Devuelve el id de la fila, o
     * vacio si el evento ya estaba registrado.
     */
    @Transactional
    public Optional<String> record(String rawPayload) {
        return store(rawPayload).map(WebhookEventEntity::getId);
    }

    private Optional<WebhookEventEntity> store(String rawPayload) {
        KiraWebhookEnvelope envelope = KiraWebhookEnvelope.from(objectMapper.readTree(rawPayload));

        if (envelope.eventId() == null) {
            log.warn("Webhook sin data.event_id, evento '{}'. Se registra pero no se deduplica.",
                    envelope.eventName());
        } else if (events.existsByEventId(envelope.eventId())) {
            log.debug("Evento {} ya registrado; se ignora.", envelope.eventId());
            return Optional.empty();
        }

        WebhookEventEntity stored = new WebhookEventEntity();
        stored.setId(UUID.randomUUID().toString());
        stored.setEventId(envelope.eventId() != null ? envelope.eventId() : "no-id:" + UUID.randomUUID());
        stored.setEventType(envelope.eventName() != null ? envelope.eventName() : "unknown");
        stored.setPayload(rawPayload);
        stored.setCreatedAt(Instant.now());

        try {
            events.saveAndFlush(stored);
        } catch (DataIntegrityViolationException duplicate) {
            // Carrera entre dos entregas del mismo evento: la restriccion unica es la autoridad.
            log.debug("Evento {} insertado en paralelo; se ignora.", envelope.eventId());
            return Optional.empty();
        }
        return Optional.of(stored);
    }

    /**
     * Paso 2, despues de responder: proyecta el evento ya guardado.
     *
     * Nunca propaga: Kira ya tiene su 2xx. Si falla, la fila queda con processed = false y
     * processing_error, y la recoge WebhookReprojectionWorker.
     */
    @Async(AsyncConfig.WEBHOOK_EXECUTOR)
    public void projectLater(String storedId) {
        events.findById(storedId).ifPresent(stored -> {
            try {
                self.reproject(stored);
            } catch (Exception e) {
                stored.setProcessingError(truncate(e.getMessage()));
                events.save(stored);
                if (metrics != null) {
                    metrics.webhookProjectionFailed(stored.getEventType());
                }
                log.error("Evento {} almacenado pero no proyectado: {}", stored.getEventId(), e.getMessage());
            }
        });
    }

    /** Guardar y proyectar en el mismo hilo. Lo usan las pruebas y quien no pasa por HTTP. */
    @Transactional
    public void process(String rawPayload) throws Exception {
        Optional<WebhookEventEntity> saved = store(rawPayload);
        if (saved.isEmpty()) {
            return;
        }
        WebhookEventEntity stored = saved.get();
        KiraWebhookEnvelope envelope = KiraWebhookEnvelope.from(objectMapper.readTree(rawPayload));
        try {
            applyProjection(envelope, stored);
            stored.setProcessed(true);
            stored.setProcessedAt(Instant.now());
        } catch (Exception e) {
            // processed sigue en false: la fila queda como material del reconciliador.
            stored.setProcessingError(truncate(e.getMessage()));
            log.error("Evento {} almacenado pero no proyectado: {}", stored.getEventId(), e.getMessage());
        }
        events.save(stored);
    }

    /**
     * Reintenta la proyeccion de un evento ya almacenado que nunca se proyecto.
     *
     * No se puede reutilizar {@link #process(String)}: ese metodo empieza deduplicando por
     * event_id y, como la fila ya existe, saldria sin proyectar nada, que es justo lo contrario
     * de lo que se busca aqui.
     *
     * Deja que la excepcion suba: quien llama decide si la fila queda marcada con el error o si
     * el lote entero se corta (por ejemplo, cuando faltan las credenciales de Kira).
     */
    @Transactional
    public void reproject(WebhookEventEntity stored) throws Exception {
        KiraWebhookEnvelope envelope = KiraWebhookEnvelope.from(objectMapper.readTree(stored.getPayload()));
        applyProjection(envelope, stored);
        stored.setProcessed(true);
        stored.setProcessedAt(Instant.now());
        stored.setProcessingError(null);
        events.save(stored);
    }

    /**
     * Proyecta el evento sobre el modelo de lectura propio.
     * Los pagos usan dos familias solapadas de eventos: payout.* y payout.status_changed
     * pueden traer la misma transicion. Se decide por el VALOR del estado, no por el nombre.
     */
    private void applyProjection(KiraWebhookEnvelope envelope, WebhookEventEntity stored) {
        String name = envelope.eventName() == null ? "" : envelope.eventName();

        if (name.startsWith("payout.")) {
            String payoutId = firstNonNull(envelope.text("payout_id"), envelope.text("id"));
            stored.setResourceId(payoutId);

            PayoutStatus incoming = PayoutStatus.fromWire(envelope.rawStatus());
            stored.setNormalizedStatus(incoming.name());

            if (payoutId != null) {
                Optional<Payout> found = payouts.findByKiraPayoutId(payoutId);
                if (found.isPresent()) {
                    Payout payout = found.get();
                    PayoutStatus antes = payout.getStatus();
                    // El error_code vive en el recurso, no en el payload; se completa al reconciliar.
                    payout.applyRemoteStatus(incoming, "payout.returned".equals(name)
                            ? "va-payout-bank-returned" : null);
                    payouts.save(payout);
                    stored.setTenantId(payout.getTenantId().value());
                    if (antes != payout.getStatus()) {
                        notifyPayout(payout, "payout.returned".equals(name));
                    }
                } else {
                    log.info("Evento de payout {} sin correspondencia local. Pendiente de reconciliacion.",
                            payoutId);
                }
            }
            return;
        }

        if (name.startsWith("user.")) {
            applyUserEvent(name, envelope, stored);
            return;
        }

        if (name.startsWith("virtual_account.")) {
            applyVirtualAccountEvent(name, envelope, stored);
            return;
        }

        if (name.startsWith("rfi.")) {
            // rfi.* exige suscripcion explicita en Kira: si no llega, lo cubre POST /api/rfis/sync.
            String kiraRfiId = firstNonNull(envelope.text("rfi_id"), envelope.text("id"));
            String rawStatus = firstNonNull(
                    envelope.rawStatus(), envelope.text("new_status"), envelope.text("to_status"));
            stored.setResourceId(kiraRfiId);
            stored.setNormalizedStatus(rawStatus);
            rfis.applyWebhook(kiraRfiId, rawStatus);
            tenants.findByKiraUserId(envelope.text("user_id")).ifPresent(tenant -> {
                stored.setTenantId(tenant.getId().value());
                notifyRfi(tenant, name, envelope);
            });
            return;
        }

        // Tipos nuevos: se registran y se reconocen. Nunca se responde 4xx: es lo unico que Kira no reintenta.
        log.info("Evento de tipo no manejado '{}' almacenado para revision.", name);
    }

    /**
     * Familia virtual_account.*.
     *
     * virtual_account.activated es la UNICA senal inequivoca de fondos-listos: el estado
     * 'approved' colapsa activating y active, asi que por si solo no dice si la cuenta
     * puede mover dinero.
     */
    private void applyVirtualAccountEvent(String name, KiraWebhookEnvelope envelope,
                                          WebhookEventEntity stored) {
        String kiraAccountId = firstNonNull(
                envelope.text("virtual_account_id"), envelope.text("id"));
        stored.setResourceId(kiraAccountId);
        stored.setNormalizedStatus(envelope.rawStatus());

        // Seis eventos distintos describen el mismo deposito en momentos distintos.
        if (name.contains("deposit")) {
            KiraDepositEvent deposit = KiraDepositEvent.from(name, envelope.payload());
            stored.setNormalizedStatus(deposit.status().name());
            deposits.apply(deposit);
            if (kiraAccountId != null) {
                accounts.findByKiraAccountId(kiraAccountId).ifPresent(account -> {
                    stored.setTenantId(account.getTenantId().value());
                    notifyDeposit(account, deposit);
                });
            }
            return;
        }

        if (kiraAccountId == null) {
            return;
        }
        Optional<VirtualAccount> found = accounts.findByKiraAccountId(kiraAccountId);
        if (found.isEmpty()) {
            log.info("Evento de cuenta virtual {} sin correspondencia local.", kiraAccountId);
            return;
        }
        VirtualAccount account = found.get();
        stored.setTenantId(account.getTenantId().value());
        boolean listaAntes = account.isFundsReady();
        VirtualAccountStatus estadoAntes = account.getStatus();

        if ("virtual_account.activated".equals(name)) {
            account.markActivatedEventSeen();
        } else if (envelope.rawStatus() != null) {
            account.applyRemoteStatus(VirtualAccountStatus.fromWire(envelope.rawStatus()));
        }
        if (!listaAntes && account.isFundsReady()) {
            notifications.notify(account.getTenantId(), "account.activated", "success",
                    "Cuenta virtual operativa", "La cuenta ya puede recibir fondos.", "virtual_account", account.getId());
        } else if (estadoAntes != VirtualAccountStatus.FROZEN && account.getStatus() == VirtualAccountStatus.FROZEN) {
            notifications.notify(account.getTenantId(), "account.frozen", "critical",
                    "Cuenta virtual congelada", "El proveedor congelo la cuenta; no puede enviar pagos.",
                    "virtual_account", account.getId());
        }
        // El numero de cuenta real es la otra senal de fondos-listos.
        account.describeBank(envelope.text("bank_name"), envelope.text("account_number"),
                envelope.text("routing_number"));
        accounts.save(account);
    }

    /**
     * Familia user.*: el KYB de la empresa y la prueba de vida de sus beneficiarios.
     *
     * Dos de estos eventos son la UNICA fuente de su dato: user.verification.failed trae el
     * motivo del rechazo, que el GET nunca expone, y user.liveness_completed trae el
     * resultado real de la prueba, que la landing de redireccion no puede confirmar.
     * Perderlos aqui es perderlos para siempre.
     */
    private void applyUserEvent(String name, KiraWebhookEnvelope envelope, WebhookEventEntity stored) {
        String kiraUserId = firstNonNull(envelope.text("user_id"), envelope.text("id"));
        stored.setResourceId(kiraUserId);
        // user.status_changed trae new_status, no status (webhooks/notification-examples).
        String remoteStatus = firstNonNull(
                envelope.rawStatus(), envelope.text("new_status"), envelope.text("to_status"));
        stored.setNormalizedStatus(remoteStatus);

        if ("user.liveness_completed".equals(name)) {
            // El resultado viaja en 'result' ("approved"); se refiere a la persona, no a la empresa.
            String personReferenceId = firstNonNull(
                    envelope.text("person_reference_id"), envelope.text("subject_id"));
            if (personReferenceId == null) {
                // Documentado: null cuando la prueba era de la propia empresa y no de una persona.
                log.info("Liveness de la empresa {} sin persona asociada: no afecta a ningun beneficiario.",
                        kiraUserId);
                return;
            }
            LivenessStatus resultado = LivenessStatus.fromWire(firstNonNull(envelope.text("result"), envelope.rawStatus()));
            ubos.applyLivenessResult(personReferenceId, resultado);
            if (kiraUserId != null) {
                tenants.findByKiraUserId(kiraUserId).ifPresent(tenant -> {
                    stored.setTenantId(tenant.getId().value());
                    if (resultado.isFinal()) {
                        boolean ok = resultado == LivenessStatus.COMPLETED;
                        notifications.notify(tenant.getId(), "onboarding.liveness", ok ? "success" : "critical",
                                ok ? "Prueba de vida completada" : "Prueba de vida no superada",
                                ok ? "Un beneficiario completo su verificacion de identidad."
                                        : "Un beneficiario no supero la verificacion; revisa la vinculacion.",
                                "tenant", tenant.getId().value());
                    }
                });
            }
            return;
        }

        if (kiraUserId == null) {
            log.warn("Evento '{}' sin identificador de usuario de Kira.", name);
            return;
        }
        Optional<Tenant> found = tenants.findByKiraUserId(kiraUserId);
        if (found.isEmpty()) {
            log.info("Evento de usuario {} sin correspondencia local. Pendiente de reconciliacion.",
                    kiraUserId);
            return;
        }
        Tenant tenant = found.get();
        stored.setTenantId(tenant.getId().value());
        TenantStatus estadoAntes = tenant.getStatus();

        if ("user.verification.failed".equals(name)) {
            tenant.rejectVerification(firstNonNull(reasonsOf(envelope.payload()), envelope.text("reason"),
                    envelope.text("rejection_reason"), envelope.text("message"),
                    "Kira rechazo la verificacion sin detallar el motivo."));
        } else if ("user.verification.accepted".equals(name)) {
            tenant.applyRemoteState(TenantStatus.VERIFIED, null, null, true);
        } else if (remoteStatus != null) {
            // user.created, user.updated, user.status_changed: solo mueven el estado.
            // Se exige status explicito: fromWire cae a CREATED ante un valor ausente, y
            // aplicar eso degradaria a CREATED una empresa ya verificada.
            tenant.applyRemoteState(TenantStatus.fromWire(remoteStatus), null, null, null);
        } else {
            log.debug("Evento '{}' sin status: se registra sin mover el estado de la empresa.", name);
            return;
        }
        tenants.save(tenant);
        if (estadoAntes != tenant.getStatus()) {
            notifyTenantStatus(tenant);
        }
    }

    // ---------- Avisos ----------

    private void notifyTenantStatus(Tenant tenant) {
        switch (tenant.getStatus()) {
            case VERIFIED -> notifications.notify(tenant.getId(), "onboarding.verified", "success",
                    "Vinculacion aprobada", "El proveedor verifico la empresa.", "tenant", tenant.getId().value());
            case REJECTED -> notifications.notify(tenant.getId(), "onboarding.rejected", "critical",
                    "Vinculacion no aprobada", tenant.getRejectionReason(), "tenant", tenant.getId().value());
            case REVIEW -> notifications.notify(tenant.getId(), "onboarding.review", "attention",
                    "Vinculacion en revision manual", "El proveedor revisa el expediente.", "tenant",
                    tenant.getId().value());
            default -> {
                // CREATED y VERIFYING no piden nada a la organizacion: no se avisa.
            }
        }
    }

    private void notifyPayout(Payout payout, boolean returned) {
        String id = payout.getId();
        switch (payout.getStatus()) {
            case COMPLETED -> notifications.notify(payout.getTenantId(), "payout.completed", "success",
                    "Pago completado", "El destinatario recibio el pago.", "payout", id);
            case FAILED -> notifications.notify(payout.getTenantId(), "payout.failed", "critical",
                    returned ? "Pago devuelto por el banco" : "Pago fallido",
                    "Revisa el detalle del pago.", "payout", id);
            case CANCELLED -> notifications.notify(payout.getTenantId(), "payout.cancelled", "info",
                    "Pago cancelado", "Se detuvo antes de enviarse.", "payout", id);
            case IN_REVIEW, KYT_PENDING -> notifications.notify(payout.getTenantId(), "payout.held", "attention",
                    "Pago en revision", "El proveedor retuvo el pago para un control.", "payout", id);
            default -> {
                // Los estados en curso no merecen aviso: se ven en la pantalla del pago.
            }
        }
    }

    private void notifyDeposit(VirtualAccount account, KiraDepositEvent deposit) {
        switch (deposit.status()) {
            case COMPLETED -> {
                if (!deposit.microdeposit()) {
                    notifications.notify(account.getTenantId(), "deposit.received", "success",
                            "Deposito recibido", "Llegaron fondos a una cuenta virtual.", "virtual_account",
                            account.getId());
                }
            }
            case REFUNDED -> notifications.notify(account.getTenantId(), "deposit.refunded", "attention",
                    "Deposito devuelto", "Un deposito se devolvio al ordenante.", "virtual_account", account.getId());
            case KYT_PENDING, KYT_REJECTED -> notifications.notify(account.getTenantId(), "deposit.held", "critical",
                    "Deposito retenido por cumplimiento",
                    "Mientras dure, la cuenta no puede enviar pagos.", "virtual_account", account.getId());
            default -> {
            }
        }
    }

    private void notifyRfi(Tenant tenant, String name, KiraWebhookEnvelope envelope) {
        String rfiId = firstNonNull(envelope.text("rfi_id"), envelope.text("id"));
        switch (name) {
            case "rfi.raised" -> notifications.notify(tenant.getId(), "rfi.raised", "attention",
                    "Nueva solicitud de informacion", "El proveedor necesita informacion; tiene plazo.", "rfi", rfiId);
            case "rfi.item_returned" -> notifications.notify(tenant.getId(), "rfi.item_returned", "attention",
                    "Respuesta devuelta", firstNonNull(envelope.text("review_note"),
                            "Una respuesta no fue aceptada y se pide de nuevo."), "rfi", rfiId);
            case "rfi.resolved" -> notifications.notify(tenant.getId(), "rfi.resolved", "success",
                    "Solicitud resuelta", "Lo que estaba detenido queda liberado.", "rfi", rfiId);
            case "rfi.not_resolved" -> notifications.notify(tenant.getId(), "rfi.not_resolved", "critical",
                    "Solicitud cerrada sin resolver", envelope.text("resolution_reason"), "rfi", rfiId);
            default -> {
            }
        }
    }

    /** data.reasons[] de user.verification.failed, unidos. Es el unico sitio donde existen. */
    private static String reasonsOf(JsonNode payload) {
        JsonNode reasons = payload.path("reasons");
        if (!reasons.isArray() || reasons.isEmpty()) {
            return null;
        }
        java.util.List<String> texts = new java.util.ArrayList<>();
        reasons.forEach(r -> {
            if (!r.isNull() && !r.asText().isBlank()) {
                texts.add(r.asText().trim());
            }
        });
        return texts.isEmpty() ? null : String.join("; ", texts);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
