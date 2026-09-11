package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.treasury.FeeBreakdown;
import com.example.autransactional.domain.treasury.Quotation;
import com.example.autransactional.domain.treasury.QuotationRail;
import com.example.autransactional.domain.treasury.QuotationRepository;
import com.example.autransactional.domain.treasury.Recipient;
import com.example.autransactional.domain.treasury.RecipientRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraAmounts;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cotizacion de una transferencia contra POST /v1/quotations.
 *
 * Se cotiza en modo redimible (con virtual_account_id) y con inverse=true: el importe que
 * teclea el operador es lo que recibe el destinatario, y las comisiones se suman por
 * encima. El modo preview (quote_for) devuelve quote_id nulo y no sirve para pagar.
 */
@Service
public class CreateQuoteService {

    private static final Logger log = LoggerFactory.getLogger(CreateQuoteService.class);

    /** Markup porcentual de la plataforma. Hoy el margen es solo fijo. */
    private static final int PLATFORM_MARKUP_BPS = 0;

    private final QuotationRepository quotations;
    private final RecipientRepository recipients;
    private final VirtualAccountRepository accounts;
    private final TenantRepository tenants;
    private final KiraApiClient kira;
    private final AuditTrail audit;

    public CreateQuoteService(QuotationRepository quotations, RecipientRepository recipients,
                              VirtualAccountRepository accounts, TenantRepository tenants,
                              KiraApiClient kira, AuditTrail audit) {
        this.quotations = quotations;
        this.recipients = recipients;
        this.accounts = accounts;
        this.tenants = tenants;
        this.kira = kira;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<QuotationView> list(AuthenticatedOperator operator, int limit) {
        return quotations.findByTenant(operator.tenantId(), Math.min(limit, 100))
                .stream().map(QuotationView::from).toList();
    }

    @Transactional(readOnly = true)
    public QuotationView get(AuthenticatedOperator operator, String quotationId) {
        return QuotationView.from(load(operator.tenantId(), quotationId));
    }

    @Transactional
    public QuotationView create(AuthenticatedOperator operator, QuotationCommands.CreateQuote command) {
        if (!operator.role().canCreatePayout()) {
            throw new DomainException("Tu rol no puede cotizar transferencias.");
        }

        Tenant tenant = tenants.findById(operator.tenantId())
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
        tenant.assertCanOperateTreasury();

        VirtualAccount account = accounts.findByIdAndTenant(command.virtualAccountId(), operator.tenantId())
                .orElseThrow(() -> new DomainException("La cuenta virtual no existe."));
        // 'approved' no basta: hace falta numero de cuenta real o el evento de activacion.
        account.assertFundsReady();
        if (account.getKiraAccountId() == null) {
            throw new DomainException("La cuenta virtual no esta abierta en Kira.");
        }

        Recipient recipient = recipients.findByIdAndTenant(command.recipientId(), operator.tenantId())
                .orElseThrow(() -> new DomainException("El destinatario no existe."));
        recipient.assertUsable();

        QuotationRail rail = resolveRail(command.rail(), recipient);

        Quotation quotation = new Quotation(
                UUID.randomUUID().toString(),
                operator.tenantId(),
                account.getId(),
                recipient.getId(),
                rail,
                command.amount(),
                // Estimacion hasta que Kira liquide: su tarifa depende del riel.
                FeeBreakdown.standard(),
                Instant.now().plus(Duration.ofSeconds(Quotation.TTL_SECONDS)));

        JsonNode response = kira.createQuotation(buildBody(account, rail, command));
        KiraQuoteResponse quote = KiraQuoteResponse.from(response);

        quotation.applyKiraQuote(quote.quoteId(), quote.expiresAt(), quote.sourceAmount(),
                quote.recipientAmount(), quote.recipientCurrency(), quote.exchangeRate(),
                quote.fees(), quote.balanceSufficient(), quote.rateSource(), quote.feesSnapshot());

        if (!quotation.hasConsistentTotals()) {
            // No se bloquea: manda lo que Kira cobra. Pero tiene que quedar registrado.
            log.warn("Cotizacion {}: el bruto {} no cuadra con {} + comisiones {}.",
                    quotation.getId(), quotation.getTotalDebitAmount(), quotation.getOriginAmount(),
                    quotation.getFees().totalFee());
        }
        if (quotation.usesFallbackRate()) {
            log.warn("Cotizacion {} calculada con tasa de contingencia ({}).",
                    quotation.getId(), quotation.getRateSource());
        }

        quotations.save(quotation);
        audit.record(operator, "quotation.created", "quotation", quotation.getId(), null, "OK",
                "kira_quote_id=" + quote.quoteId() + " riel=" + rail
                        + " saldo_suficiente=" + quote.balanceSufficient());

        return QuotationView.from(quotation);
    }

    /**
     * Cuerpo de POST /v1/quotations.
     *
     * from_held_balance debe ser true para pagar desde el saldo de la cuenta virtual, e
     * inverse=true hace que el motor compense hacia arriba: el destinatario recibe el
     * importe exacto y las comisiones se suman al debito.
     */
    private Map<String, Object> buildBody(VirtualAccount account, QuotationRail rail,
                                          QuotationCommands.CreateQuote command) {
        Map<String, Object> body = new LinkedHashMap<>();
        // virtual_account_id y quote_for son excluyentes: enviar ambos es un 400.
        // Es el id de Kira: el id del portal no significa nada para su API.
        body.put("virtual_account_id", account.getKiraAccountId());
        body.put("amount", KiraAmounts.amountString(command.amount()));
        body.put("rail", rail.name());
        body.put("inverse", true);
        body.put("from_held_balance", true);
        body.put("client_markup", KiraAmounts.markupForQuotation(
                FeeBreakdown.requestedPlatformMarkup(), PLATFORM_MARKUP_BPS));

        String currency = command.targetCurrency() == null ? account.getCurrency() : command.targetCurrency();
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("currency", currency);
        if (rail.network() != null) {
            // La red es obligatoria en cuanto la moneda de destino es una stablecoin.
            target.put("network", rail.network());
        }
        body.put("target", target);
        return body;
    }

    /**
     * El riel se deriva del account_type del destinatario, no de lo que pida el formulario.
     * Es el punto de decision mas importante del flujo: un riel que no corresponde se
     * detecta al EJECUTAR el pago, no al cotizar, y para entonces ya se perdio el viaje.
     */
    private QuotationRail resolveRail(String requested, Recipient recipient) {
        Rail accountType = recipient.getRail();
        if (requested == null || requested.isBlank()) {
            return QuotationRail.defaultFor(accountType, recipient.getNetwork());
        }
        QuotationRail rail = QuotationRail.from(requested);
        rail.assertMatches(accountType);
        if (accountType == Rail.WALLET
                && !rail.name().equalsIgnoreCase(recipient.getNetwork())) {
            throw new DomainException("El riel " + rail + " no corresponde a la red del destinatario ("
                    + recipient.getNetwork() + ").");
        }
        return rail;
    }

    private Quotation load(TenantId tenantId, String quotationId) {
        return quotations.findByIdAndTenant(quotationId, tenantId)
                .orElseThrow(() -> new DomainException("Cotizacion no encontrada."));
    }
}
