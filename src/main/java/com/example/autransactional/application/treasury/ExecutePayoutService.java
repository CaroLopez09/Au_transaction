package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.domain.treasury.Quotation;
import com.example.autransactional.domain.treasury.QuotationRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.treasury.FeeBreakdown;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.domain.treasury.NatureOfPayment;
import com.example.autransactional.domain.treasury.SupportingDocument;
import com.example.autransactional.infrastructure.kira.KiraAmounts;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Preparacion, aprobacion y ejecucion de pagos.
 *
 * El maker-checker vive aqui porque la API de Kira no lo ofrece a los integradores:
 * el pago solo sale hacia Kira despues de que un segundo operador lo autoriza.
 */
@Service
public class ExecutePayoutService {

    private static final Logger log = LoggerFactory.getLogger(ExecutePayoutService.class);

    private final PayoutRepository payouts;
    private final QuotationRepository quotations;
    private final KiraApiClient kira;
    private final AuditTrail audit;

    public ExecutePayoutService(PayoutRepository payouts, QuotationRepository quotations,
                         KiraApiClient kira, AuditTrail audit) {
        this.payouts = payouts;
        this.quotations = quotations;
        this.kira = kira;
        this.audit = audit;
    }

    @Transactional
    public PayoutView create(AuthenticatedOperator operator, PayoutCommands.CreatePayout command) {
        if (!operator.role().canCreatePayout()) {
            throw new DomainException("Tu rol no puede preparar pagos.");
        }

        // Una clave por intencion. Se persiste para que un reintento replique el mismo pago
        // en Kira en vez de crear uno nuevo.
        IdempotencyKey key = IdempotencyKey.newKey();

        // El desglose comisional vigente: 15 USD de Kira + 15 USD de margen = 30 USD.
        Payout payout = new Payout(
                UUID.randomUUID().toString(),
                operator.tenantId(),
                command.kiraUserId(),
                command.virtualAccountId(),
                command.recipientId(),
                Money.of(command.amount(), command.currency()),
                FeeBreakdown.standard(),
                key,
                operator.userId());

        // Con cotizacion, las comisiones reales son las que liquido Kira, no la estimacion.
        if (command.quotationId() != null && !command.quotationId().isBlank()) {
            Quotation quotation = quotations.findByIdAndTenant(command.quotationId(), operator.tenantId())
                    .orElseThrow(() -> new DomainException("Cotizacion no encontrada."));
            payout.attachQuotation(quotation, Instant.now());
        }

        payouts.save(payout);
        audit.record(operator, "payout.created", "payout", payout.getId(), key.value(), "OK", null);
        return PayoutView.from(payout);
    }

    @Transactional
    public PayoutView approveAndSubmit(AuthenticatedOperator operator, String payoutId,
                                       PayoutCommands.ApprovePayout command) {
        if (!operator.role().canApprovePayout()) {
            throw new DomainException("Tu rol no puede autorizar pagos.");
        }

        Instant now = Instant.now();
        Payout payout = load(operator.tenantId(), payoutId);
        Quotation quotation = loadQuotation(operator, payout, now);

        payout.approve(operator.userId(), now);
        payouts.save(payout);
        audit.record(operator, "payout.approved", "payout", payout.getId(),
                payout.getIdempotencyKey().value(), "OK",
                payout.isPriceLocked() ? "cotizacion=" + payout.getQuotationId() : "sin cotizacion");

        return PayoutView.from(submitToKira(operator, payout, quotation, command));
    }

    @Transactional
    public PayoutView reject(AuthenticatedOperator operator, String payoutId, String reason) {
        if (!operator.role().canApprovePayout()) {
            throw new DomainException("Tu rol no puede rechazar pagos.");
        }
        Payout payout = load(operator.tenantId(), payoutId);
        payout.reject(operator.userId(), reason);
        payouts.save(payout);
        audit.record(operator, "payout.rejected", "payout", payout.getId(), null, "OK", reason);
        return PayoutView.from(payout);
    }

    /** Reconciliacion puntual: los eventos llegan una sola vez, el recurso es la autoridad final. */
    @Transactional
    public PayoutView refreshFromKira(AuthenticatedOperator operator, String payoutId) {
        Payout payout = load(operator.tenantId(), payoutId);
        if (payout.getKiraPayoutId() == null) {
            return PayoutView.from(payout);
        }
        JsonNode remote = kira.getPayout(payout.getKiraPayoutId());
        JsonNode body = remote.has("data") ? remote.get("data") : remote;

        payout.applyRemoteStatus(
                com.example.autransactional.domain.treasury.PayoutStatus.fromWire(body.path("status").asText(null)),
                body.path("error_code").asText(null));
        // reference_number es el comprobante para el cliente final y solo vive en el recurso.
        payout.describeRemote(body.path("reference_number").asText(null),
                body.path("payment_method").asText(null));
        payouts.save(payout);
        return PayoutView.from(payout);
    }

    @Transactional(readOnly = true)
    public List<PayoutView> list(AuthenticatedOperator operator, int limit) {
        return payouts.findByTenant(operator.tenantId(), Math.min(limit, 100))
                .stream().map(PayoutView::from).toList();
    }

    @Transactional(readOnly = true)
    public PayoutView get(AuthenticatedOperator operator, String payoutId) {
        return PayoutView.from(load(operator.tenantId(), payoutId));
    }

    private Payout submitToKira(AuthenticatedOperator operator, Payout payout, Quotation quotation,
                                PayoutCommands.ApprovePayout command) {
        Instant now = Instant.now();
        payout.assertSubmittable(now);

        Map<String, Object> body = buildBody(payout, quotation, command);

        try {
            JsonNode response = kira.executePayout(payout.getVirtualAccountId(), body,
                    payout.getIdempotencyKey());
            JsonNode data = response.has("data") ? response.get("data") : response;

            // El 201 de creacion identifica el pago con 'id'; el GET lo llama 'payout_id'.
            String kiraPayoutId = data.path("id").asText(null);
            if (kiraPayoutId == null) {
                kiraPayoutId = data.path("payout_id").asText(null);
            }
            payout.markAsSubmitted(kiraPayoutId, data.path("status").asText(null));
            payout.describeRemote(data.path("reference_number").asText(null),
                    data.path("payment_method").asText(null));

            // La cotizacion se consume ahora, no al preparar el pago.
            if (quotation != null) {
                quotation.markExecuted();
                quotations.save(quotation);
            }
            payouts.save(payout);
            audit.record(operator, "payout.submitted", "payout", payout.getId(),
                    payout.getIdempotencyKey().value(), "OK",
                    "kira_payout_id=" + kiraPayoutId + " bruto=" + body.get("amount"));

        } catch (RuntimeException e) {
            log.error("Fallo el envio del pago {} a Kira: {}", payout.getId(), e.getMessage());
            // Las validaciones del quote fallan con 400 ANTES de consumirlo: sigue redimible,
            // asi que no se marca como ejecutado y el reintento reutiliza la misma clave.
            payout.fail(e.getMessage());
            payouts.save(payout);
            audit.record(operator, "payout.submitted", "payout", payout.getId(),
                    payout.getIdempotencyKey().value(), "ERROR", e.getMessage());
            throw e;
        }
        return payout;
    }

    /**
     * Cuerpo de POST /v1/virtual-accounts/{id}/payout.
     *
     * El 'amount' es el BRUTO: Kira descuenta las comisiones de ese numero en vez de sumarlas
     * encima, asi que enviar lo que teclea el operador dejaria al destinatario cobrando de
     * menos. Con cotizacion, el bruto correcto es el total a debitar que Kira ya calculo.
     */
    private Map<String, Object> buildBody(Payout payout, Quotation quotation,
                                          PayoutCommands.ApprovePayout command) {
        Map<String, Object> body = new LinkedHashMap<>();
        // recipient_id va en el nivel superior, no anidado bajo destination.
        body.put("recipient_id", payout.getRecipientId());
        body.put("amount", KiraAmounts.amountString(payout.grossAmountToSend(quotation).amount()));

        if (quotation != null) {
            // Con quote_id el precio ya esta cerrado, markup incluido: reenviarlo lo cobraria dos veces.
            body.put("quote_id", quotation.getKiraQuoteId());
        } else {
            // Sin cotizacion el precio no esta cerrado; el margen tiene que viajar aqui,
            // y en esta ruta la API lo espera como cadenas decimales, no en unidades menores.
            body.put("client_markup", KiraAmounts.markupForPayout(
                    payout.getFees().platformFee(), 0));
        }

        NatureOfPayment nature = NatureOfPayment.from(command == null ? null : command.natureOfPayment());
        if (nature != null) {
            body.put("nature_of_payment", nature.wireValue());
        }

        List<SupportingDocument> documents = command == null ? List.of() : command.documents();
        SupportingDocument.assertValid(documents, nature, false);
        if (documents != null && !documents.isEmpty()) {
            List<Map<String, Object>> files = new ArrayList<>();
            for (SupportingDocument document : documents) {
                files.add(Map.of("type", document.type(), "file", document.file()));
            }
            body.put("supporting_documents", files);
        }

        Map<String, Object> extraInfo = new LinkedHashMap<>();
        if (command != null && command.memo() != null && !command.memo().isBlank()) {
            extraInfo.put("memo", command.memo().length() > 255
                    ? command.memo().substring(0, 255) : command.memo());
        }
        if (command != null && command.comment() != null && !command.comment().isBlank()) {
            extraInfo.put("internal_notes", command.comment().length() > 1000
                    ? command.comment().substring(0, 1000) : command.comment());
        }
        if (!extraInfo.isEmpty()) {
            body.put("extra_info", extraInfo);
        }
        return body;
    }

    /** La cotizacion atada, validada como redimible. Null si el pago no lleva ninguna. */
    private Quotation loadQuotation(AuthenticatedOperator operator, Payout payout, Instant now) {
        if (!payout.isPriceLocked()) {
            log.warn("El pago {} se aprueba sin cotizacion: el precio no esta cerrado.", payout.getId());
            return null;
        }
        Quotation quotation = quotations.findByIdAndTenant(payout.getQuotationId(), operator.tenantId())
                .orElseThrow(() -> new DomainException("La cotizacion del pago no existe."));
        // Vigente, redimible y con saldo: Kira devuelve 400 terminal si falta lo ultimo.
        quotation.assertRedeemable(now);
        return quotation;
    }

    private Payout load(TenantId tenantId, String payoutId) {
        // El filtro por tenant es parte de la consulta: un id manipulado no cruza organizaciones.
        return payouts.findByIdAndTenant(payoutId, tenantId)
                .orElseThrow(() -> new DomainException("Pago no encontrado."));
    }
}
