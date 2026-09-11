package com.example.autransactional.application.webhook;

import com.example.autransactional.application.account.KiraDepositEvent;
import com.example.autransactional.application.account.RecordDepositService;
import com.example.autransactional.application.compliance.AnswerRfiService;
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
import com.example.autransactional.infrastructure.persistence.WebhookEventEntity;
import com.example.autransactional.infrastructure.persistence.WebhookEventJpaRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * El ingress ya respondio 2xx: aqui no se puede pedir un reintento al emisor porque no lo hay.
 * La idempotencia se apoya en la unicidad de data.event_id en base de datos.
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

    public ProcessWebhookUseCase(WebhookEventJpaRepository events, PayoutRepository payouts,
                                 TenantRepository tenants, VirtualAccountRepository accounts,
                                 RecordDepositService deposits, SyncUbosService ubos,
                                 AnswerRfiService rfis, ObjectMapper objectMapper) {
        this.events = events;
        this.payouts = payouts;
        this.tenants = tenants;
        this.accounts = accounts;
        this.deposits = deposits;
        this.ubos = ubos;
        this.rfis = rfis;
        this.objectMapper = objectMapper;
    }

    @Async(AsyncConfig.WEBHOOK_EXECUTOR)
    public void enqueue(String rawPayload) {
        try {
            process(rawPayload);
        } catch (Exception e) {
            // Nunca propagamos: el emisor ya recibio su 200 y no reintenta.
            log.error("Fallo procesando webhook de Kira: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void process(String rawPayload) throws Exception {
        JsonNode root = objectMapper.readTree(rawPayload);
        KiraWebhookEnvelope envelope = KiraWebhookEnvelope.from(root);

        if (envelope.eventId() == null) {
            log.warn("Webhook sin data.event_id, evento '{}'. Se registra pero no se deduplica.",
                    envelope.eventName());
        } else if (events.existsByEventId(envelope.eventId())) {
            log.debug("Evento {} ya procesado; se ignora.", envelope.eventId());
            return;
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
            return;
        }

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
                    // El error_code vive en el recurso, no en el payload; se completa al reconciliar.
                    payout.applyRemoteStatus(incoming, "payout.returned".equals(name)
                            ? "va-payout-bank-returned" : null);
                    payouts.save(payout);
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
            return;
        }

        // Tipos nuevos: se registran y se reconocen. Nunca se responde 4xx, el emisor no reintenta.
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

        if ("virtual_account.activated".equals(name)) {
            account.markActivatedEventSeen();
        } else if (envelope.rawStatus() != null) {
            account.applyRemoteStatus(VirtualAccountStatus.fromWire(envelope.rawStatus()));
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
        stored.setNormalizedStatus(firstNonNull(
                envelope.rawStatus(), envelope.text("new_status"), envelope.text("to_status")));

        if ("user.liveness_completed".equals(name)) {
            // El estado del evento se refiere a la persona, no a la empresa.
            ubos.applyLivenessResult(
                    firstNonNull(envelope.text("person_reference_id"), envelope.text("subject_id")),
                    LivenessStatus.fromWire(envelope.rawStatus()));
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

        if ("user.verification.failed".equals(name)) {
            tenant.rejectVerification(firstNonNull(envelope.text("reason"),
                    envelope.text("rejection_reason"), envelope.text("message"),
                    "Kira rechazo la verificacion sin detallar el motivo."));
        } else if ("user.verification.accepted".equals(name)) {
            tenant.applyRemoteState(TenantStatus.VERIFIED, null, null, true);
        } else if (envelope.rawStatus() != null) {
            // user.created, user.updated, user.status_changed: solo mueven el estado.
            // Se exige status explicito: fromWire cae a CREATED ante un valor ausente, y
            // aplicar eso degradaria a CREATED una empresa ya verificada.
            tenant.applyRemoteState(TenantStatus.fromWire(envelope.rawStatus()), null, null, null);
        } else {
            log.debug("Evento '{}' sin status: se registra sin mover el estado de la empresa.", name);
            return;
        }
        tenants.save(tenant);
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
