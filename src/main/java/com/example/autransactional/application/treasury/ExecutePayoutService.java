package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.compliance.Rfi;
import com.example.autransactional.domain.compliance.RfiRepository;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.Recipient;
import com.example.autransactional.domain.treasury.RecipientRepository;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.domain.treasury.PayoutApprovalState;
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
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
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
    private final VirtualAccountRepository accounts;
    private final RecipientRepository recipients;
    private final TenantRepository tenants;
    private final RfiRepository rfis;
    private final KiraApiClient kira;
    private final AuditTrail audit;
    private final ObjectMapper objectMapper;
    private final PayoutApprovalPolicy approvalPolicy;
    private final CreateQuoteService quotes;
    private final OperatorUserRepository users;

    /** Filtros que acepta GET /v1/payouts: cualquier otro parametro lo rechaza con 400. */
    private static final Set<String> KIRA_PAYOUT_STATUSES = Set.of(
            "CREATED", "PENDING", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED", "IN_REVIEW", "KYT_PENDING");

    public ExecutePayoutService(PayoutRepository payouts, QuotationRepository quotations,
                                VirtualAccountRepository accounts, RecipientRepository recipients,
                                TenantRepository tenants, RfiRepository rfis, KiraApiClient kira,
                                AuditTrail audit, ObjectMapper objectMapper, PayoutApprovalPolicy approvalPolicy,
                                CreateQuoteService quotes, OperatorUserRepository users) {
        this.quotes = quotes;
        this.users = users;
        this.payouts = payouts;
        this.quotations = quotations;
        this.accounts = accounts;
        this.recipients = recipients;
        this.tenants = tenants;
        this.rfis = rfis;
        this.kira = kira;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.approvalPolicy = approvalPolicy;
    }

    /**
     * Vista previa de comisiones contra POST /v1/virtual-accounts/{id}/payout/preview.
     *
     * No reserva precio ni crea nada: sirve para mostrar el coste mientras el operador teclea.
     * El margen de la plataforma viaja igual que en el pago sin cotizacion, para que lo que se
     * muestra aqui sea lo que se cobraria.
     */
    @Transactional(readOnly = true)
    public PayoutPreviewView preview(AuthenticatedOperator operator, PayoutCommands.PreviewPayout command) {
        if (!operator.role().canCreatePayout()) {
            throw new DomainException("Tu rol no puede preparar pagos.");
        }
        assertIdentityVerified(operator);
        VirtualAccount account = loadAccount(operator.tenantId(), command.virtualAccountId());
        Recipient recipient = loadRecipient(operator.tenantId(), command.recipientId());
        recipient.assertUsable();
        if (recipient.getKiraRecipientId() == null) {
            throw new DomainException("El destinatario no esta registrado en Kira.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", KiraAmounts.amountString(command.amount()));
        body.put("recipient_id", recipient.getKiraRecipientId());
        body.put("inverse_calculation", command.recipientReceivesAmount() == null || command.recipientReceivesAmount());
        body.put("client_markup", KiraAmounts.markupForPayout(FeeBreakdown.standard().platformFee(), 0));

        JsonNode data = unwrap(kira.previewPayout(account.getKiraAccountId(), body));
        JsonNode fees = data.path("fees");
        @SuppressWarnings("unchecked")
        Map<String, Object> feeMap = fees.isObject() ? objectMapper.convertValue(fees, Map.class) : Map.of();
        return new PayoutPreviewView(text(data, "amount"), text(data, "currency"),
                text(data, "recipient_amount"), text(data, "recipient_currency"), feeMap);
    }

    /** Linea de tiempo del pago (events[] de GET /v1/payouts/{id}). Vacia si aun no se envio. */
    @Transactional(readOnly = true)
    public List<PayoutEventView> events(AuthenticatedOperator operator, String payoutId) {
        Payout payout = load(operator.tenantId(), payoutId);
        if (payout.getKiraPayoutId() == null) {
            return List.of();
        }
        JsonNode body = unwrap(kira.getPayout(payout.getKiraPayoutId()));
        List<PayoutEventView> events = new ArrayList<>();
        for (JsonNode event : body.path("events")) {
            events.add(new PayoutEventView(text(event, "event_id"), text(event, "status"),
                    text(event, "message"), text(event, "created_at")));
        }
        return events;
    }

    /**
     * Historial de pagos de la empresa en Kira (GET /v1/payouts?user_id=...).
     *
     * Kira trata los pagos como globales del integrador: ademas del filtro user_id, se
     * descarta cualquier fila de otro user. Solo se envian los filtros que Kira documenta,
     * porque un parametro desconocido es un 400.
     */
    @Transactional(readOnly = true)
    public KiraPayoutPage kiraHistory(AuthenticatedOperator operator, String status, int page, int limit,
                                      String fromDate, String toDate) {
        Tenant tenant = tenants.findById(operator.tenantId())
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
        tenant.assertRegisteredInKira();

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("user_id", tenant.getKiraUserId());
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase();
            if (!KIRA_PAYOUT_STATUSES.contains(normalized)) {
                throw new DomainException("Estado no valido. Usa: " + String.join(", ", KIRA_PAYOUT_STATUSES) + ".");
            }
            query.put("status", normalized);
        }
        if (fromDate != null && !fromDate.isBlank()) {
            query.put("from_date", fromDate.trim());
        }
        if (toDate != null && !toDate.isBlank()) {
            query.put("to_date", toDate.trim());
        }
        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        query.put("page", safePage);
        query.put("limit", safeLimit);

        JsonNode response = unwrap(kira.listPayouts(query));
        List<KiraPayoutPage.Item> items = new ArrayList<>();
        for (JsonNode row : response.path("payouts")) {
            if (!tenant.getKiraUserId().equals(text(row, "user_id"))) {
                continue;
            }
            String kiraPayoutId = text(row, "payout_id");
            String local = kiraPayoutId == null ? null : payouts.findByKiraPayoutId(kiraPayoutId)
                    .filter(p -> p.getTenantId().equals(operator.tenantId()))
                    .map(Payout::getId)
                    .orElse(null);
            items.add(new KiraPayoutPage.Item(kiraPayoutId, text(row, "short_id"), local,
                    text(row, "virtual_account_id"), text(row, "status"), text(row, "origin"),
                    text(row, "from_amount"), text(row, "from_currency"), text(row, "to_amount"),
                    text(row, "to_currency"), text(row, "payment_method"), text(row, "sender_name"),
                    text(row, "recipient_name"), text(row, "reference"), text(row, "memo"),
                    text(row, "created_at")));
        }
        return new KiraPayoutPage(items, response.path("page").asInt(safePage),
                response.path("limit").asInt(safeLimit), response.path("total").asInt(items.size()),
                response.path("total_pages").asInt(1));
    }

    @Transactional
    public PayoutView create(AuthenticatedOperator operator, PayoutCommands.CreatePayout command) {
        return create(operator, command, null);
    }

    /**
     * Con la clave del portal, repetir la peticion devuelve el pago ya creado en lugar de crear
     * otro (G-07). La clave es la misma que viaja despues a Kira al aprobar.
     */
    @Transactional
    public PayoutView create(AuthenticatedOperator operator, PayoutCommands.CreatePayout command,
                             String clientIdempotencyKey) {
        if (!operator.role().canCreatePayout()) {
            throw new DomainException("Tu rol no puede preparar pagos.");
        }

        Tenant tenant = tenants.findById(operator.tenantId())
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
        tenant.assertCanOperateTreasury();
        tenant.assertRegisteredInKira();
        // Los ids son los del portal y se resuelven dentro de la empresa: un id de otra
        // organizacion, inexistente o archivado se rechaza aqui y no al aprobar.
        VirtualAccount account = loadAccount(operator.tenantId(), command.virtualAccountId());
        Recipient recipient = loadRecipient(operator.tenantId(), command.recipientId());
        recipient.assertUsable();

        // Una clave por intencion. Se persiste para que un reintento replique el mismo pago
        // en Kira en vez de crear uno nuevo.
        IdempotencyKey key = clientIdempotencyKey == null || clientIdempotencyKey.isBlank()
                ? IdempotencyKey.newKey()
                : IdempotencyKey.fromClient(clientIdempotencyKey);
        Optional<Payout> previous = payouts.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            if (!previous.get().getTenantId().equals(operator.tenantId())) {
                throw new DomainException("Esa clave de idempotencia ya esta en uso.");
            }
            return view(previous.get());
        }

        // El desglose comisional vigente: 15 USD de Kira + 15 USD de margen = 30 USD.
        Payout payout = new Payout(
                UUID.randomUUID().toString(),
                operator.tenantId(),
                // El user de Kira es el de la empresa, nunca el que diga el cuerpo de la peticion.
                tenant.getKiraUserId(),
                account.getId(),
                recipient.getId(),
                Money.of(command.amount(), command.currency()),
                FeeBreakdown.standard(),
                key,
                operator.userId());

        // Con cotizacion, las comisiones reales son las que liquido Kira, no la estimacion.
        if (command.quotationId() != null && !command.quotationId().isBlank()) {
            Quotation quotation = quotations.findByIdAndTenant(command.quotationId(), operator.tenantId())
                    .orElseThrow(() -> new DomainException("Cotizacion no encontrada."));
            if (!account.getId().equals(quotation.getVirtualAccountId())
                    || !recipient.getId().equals(quotation.getRecipientId())) {
                throw new DomainException("La cotizacion es de otra cuenta o de otro destinatario.");
            }
            payout.attachQuotation(quotation, Instant.now());
        }

        if (command.cryptoNetwork() != null && !command.cryptoNetwork().isBlank()
                || command.cryptoCurrency() != null && !command.cryptoCurrency().isBlank()) {
            payout.requestCryptoFunding(command.cryptoNetwork(), command.cryptoCurrency());
        }

        payouts.save(payout);
        audit.record(operator, "payout.created", "payout", payout.getId(), key.value(), "OK", null);
        return view(payout);
    }

    @Transactional
    public PayoutView approveAndSubmit(AuthenticatedOperator operator, String payoutId,
                                       PayoutCommands.ApprovePayout command) {
        if (!operator.role().canApprovePayout()) {
            throw new DomainException("Tu rol no puede autorizar pagos.");
        }
        assertIdentityVerified(operator);

        Instant now = Instant.now();
        Payout payout = load(operator.tenantId(), payoutId);
        Quotation quotation = loadQuotation(operator, payout, now);
        String recipientCreator = recipients.findByIdAndTenant(payout.getRecipientId(), operator.tenantId())
                .map(Recipient::getCreatedByUserId).orElse(null);
        int required = approvalPolicy.requiredApprovals(operator.tenantId(), payout.getAmount());

        boolean complete = payout.approve(operator.userId(), now, required, recipientCreator);
        payouts.save(payout);
        if (!complete) {
            // Primera de dos firmas: el pago espera a otra persona y no va todavia a Kira.
            audit.record(operator, "payout.first_approval", "payout", payout.getId(),
                    payout.getIdempotencyKey().value(), "OK", "firmas_requeridas=" + required);
            return view(payout);
        }
        audit.record(operator, "payout.approved", "payout", payout.getId(),
                payout.getIdempotencyKey().value(), "OK",
                payout.isPriceLocked() ? "cotizacion=" + payout.getQuotationId() : "sin cotizacion");

        return view(submitToKira(operator, payout, quotation, command));
    }

    /**
     * D9: la cotizacion vence a los 15 min y la aprobacion puede llegar mas tarde. Se pide una
     * nueva a Kira con la misma cuenta, destinatario, riel e importe, y el pago muestra el precio
     * nuevo antes de aprobarlo (arquitectura §7). Lo pueden pedir quien prepara y quien aprueba.
     */
    @Transactional
    public PayoutView requote(AuthenticatedOperator operator, String payoutId) {
        if (!operator.role().canCreatePayout() && !operator.role().canApprovePayout()) {
            throw new DomainException("Tu rol no puede recotizar pagos.");
        }
        Payout payout = load(operator.tenantId(), payoutId);
        if (payout.getQuotationId() == null) {
            throw new DomainException("Este pago no tiene precio fijado: no hay cotizacion que renovar.");
        }
        Quotation previous = quotations.findByIdAndTenant(payout.getQuotationId(), operator.tenantId())
                .orElseThrow(() -> new DomainException("Cotizacion no encontrada."));
        if (payout.getApprovalState() != PayoutApprovalState.PENDING_APPROVAL) {
            throw new DomainException("Solo se recotiza un pago pendiente de aprobacion.");
        }

        Quotation fresh = quotes.requote(operator, previous, payout.getAmount().amount());
        payout.replaceQuotation(fresh, Instant.now());
        previous.expire();
        quotations.save(previous);
        payouts.save(payout);
        audit.record(operator, "payout.requoted", "payout", payout.getId(), null, "OK",
                "cotizacion_anterior=" + previous.getId() + " nueva=" + fresh.getId());
        return view(payout);
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
        return view(payout);
    }

    /** Reconciliacion puntual: los eventos llegan una sola vez, el recurso es la autoridad final. */
    @Transactional
    public PayoutView refreshFromKira(AuthenticatedOperator operator, String payoutId) {
        Payout payout = load(operator.tenantId(), payoutId);
        if (payout.getKiraPayoutId() == null) {
            return view(payout);
        }
        JsonNode body = unwrap(kira.getPayout(payout.getKiraPayoutId()));

        payout.applyRemoteStatus(
                com.example.autransactional.domain.treasury.PayoutStatus.fromWire(body.path("status").asText(null)),
                body.path("error_code").asText(null));
        // reference_number es el comprobante para el cliente final y solo vive en el recurso.
        payout.describeRemote(body.path("reference_number").asText(null),
                body.path("payment_method").asText(null));
        if (payout.isCryptoFunded() && body.has("deposit_instructions")) {
            payout.recordDepositInstructions(body.get("deposit_instructions").toString());
        }
        payouts.save(payout);
        return view(payout);
    }

    @Transactional(readOnly = true)
    public List<PayoutView> list(AuthenticatedOperator operator, int limit) {
        // Una sola libreta para toda la pagina: sin ella, cien pagos del mismo maker serian
        // cien lecturas identicas a la tabla de usuarios.
        NameBook names = new NameBook();
        return payouts.findByTenant(operator.tenantId(), Math.min(limit, 100))
                .stream().map(p -> view(p, names)).toList();
    }

    @Transactional(readOnly = true)
    public PayoutView get(AuthenticatedOperator operator, String payoutId) {
        return view(load(operator.tenantId(), payoutId));
    }

    /** Vista con la marca de "detenido" si un RFI abierto de Kira bloquea el pago. */
    private PayoutView view(Payout payout) {
        return view(payout, new NameBook());
    }

    private PayoutView view(Payout payout, NameBook names) {
        String rfiId = payout.getKiraPayoutId() == null ? null : rfis.findOpenBlocking(payout.getKiraPayoutId())
                .filter(r -> r.getTenantId().equals(payout.getTenantId()))
                .map(Rfi::getId)
                .orElse(null);
        return PayoutView.from(payout, rfiId,
                approvalPolicy.requiredApprovals(payout.getTenantId(), payout.getAmount()),
                names.recipient(payout.getTenantId(), payout.getRecipientId()),
                names.operator(payout.getMakerUserId()),
                names.operator(payout.getApproverUserId()),
                names.operator(payout.getFirstApproverUserId()));
    }

    /**
     * Nombres de operadores y destinatarios resueltos una sola vez por peticion (G-03, G-26).
     *
     * Un id que ya no existe (operador borrado, destinatario de otra empresa) se memoriza como
     * ausente: el portal muestra el id y no se repite la consulta.
     */
    private final class NameBook {

        private final Map<String, String> operators = new HashMap<>();
        private final Map<String, String> recipientNames = new HashMap<>();

        String operator(String userId) {
            if (userId == null) {
                return null;
            }
            // computeIfAbsent no memoriza un null, y aqui la ausencia es justo lo que no
            // conviene volver a preguntar.
            if (!operators.containsKey(userId)) {
                operators.put(userId, users.findById(userId).map(OperatorUser::fullName).orElse(null));
            }
            return operators.get(userId);
        }

        /** El destinatario archivado tampoco esta en el directorio del portal, pero si en la base. */
        String recipient(TenantId tenantId, String recipientId) {
            if (recipientId == null) {
                return null;
            }
            if (!recipientNames.containsKey(recipientId)) {
                recipientNames.put(recipientId, recipients.findByIdAndTenant(recipientId, tenantId)
                        .map(Recipient::getName)
                        .orElse(null));
            }
            return recipientNames.get(recipientId);
        }
    }

    private static JsonNode unwrap(JsonNode response) {
        return response != null && response.has("data") ? response.get("data") : response;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Payout submitToKira(AuthenticatedOperator operator, Payout payout, Quotation quotation,
                                PayoutCommands.ApprovePayout command) {
        Instant now = Instant.now();
        payout.assertSubmittable(now);

        // Kira solo conoce sus propios ids: los del portal se traducen justo antes de enviar.
        VirtualAccount account = loadAccount(payout.getTenantId(), payout.getVirtualAccountId());
        account.assertFundsReady();
        Recipient recipient = loadRecipient(payout.getTenantId(), payout.getRecipientId());
        recipient.assertUsable();
        if (recipient.getKiraRecipientId() == null) {
            throw new DomainException("El destinatario no esta registrado en Kira.");
        }

        Map<String, Object> body = buildBody(payout, recipient, quotation, command);

        try {
            JsonNode response = kira.executePayout(account.getKiraAccountId(), body,
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
            if (payout.isCryptoFunded() && data.has("deposit_instructions")) {
                payout.recordDepositInstructions(data.get("deposit_instructions").toString());
            }

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

    /** Kira autoriza a la empresa; este BFF autoriza a la persona que inicia la transferencia. */
    private void assertIdentityVerified(AuthenticatedOperator operator) {
        OperatorUser user = users.findById(operator.userId())
                .orElseThrow(() -> new DomainException("El operador no existe."));
        if (!user.identity().status().isVerified()) {
            throw new DomainException("Completa la verificacion de identidad antes de operar transferencias.");
        }
    }

    /**
     * Cuerpo de POST /v1/virtual-accounts/{id}/payout.
     *
     * El 'amount' es el BRUTO: Kira descuenta las comisiones de ese numero en vez de sumarlas
     * encima, asi que enviar lo que teclea el operador dejaria al destinatario cobrando de
     * menos. Con cotizacion, el bruto correcto es el total a debitar que Kira ya calculo.
     */
    private Map<String, Object> buildBody(Payout payout, Recipient recipient, Quotation quotation,
                                          PayoutCommands.ApprovePayout command) {
        Map<String, Object> body = new LinkedHashMap<>();
        // recipient_id va en el nivel superior, no anidado bajo destination.
        body.put("recipient_id", recipient.getKiraRecipientId());
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

        if (payout.isCryptoFunded()) {
            // mode=CRYPTO: el pago no debita el saldo, se financia con un deposito unico.
            body.put("mode", "CRYPTO");
            body.put("payment_instructions", Map.of(
                    "network", payout.getFundingNetwork(),
                    "currency", payout.getFundingCurrency()));
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

    private VirtualAccount loadAccount(TenantId tenantId, String virtualAccountId) {
        VirtualAccount account = accounts.findByIdAndTenant(virtualAccountId, tenantId)
                .orElseThrow(() -> new DomainException("La cuenta virtual no existe."));
        if (account.getKiraAccountId() == null) {
            throw new DomainException("La cuenta virtual no esta abierta en Kira.");
        }
        return account;
    }

    private Recipient loadRecipient(TenantId tenantId, String recipientId) {
        return recipients.findByIdAndTenant(recipientId, tenantId)
                .orElseThrow(() -> new DomainException("El destinatario no existe."));
    }

    private Payout load(TenantId tenantId, String payoutId) {
        // El filtro por tenant es parte de la consulta: un id manipulado no cruza organizaciones.
        return payouts.findByIdAndTenant(payoutId, tenantId)
                .orElseThrow(() -> new DomainException("Pago no encontrado."));
    }
}
