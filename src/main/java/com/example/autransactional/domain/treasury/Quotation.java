package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cotizacion de una transferencia.
 *
 * Se cotiza con inverse=true: el importe que teclea el operador es lo que RECIBE el
 * destinatario, y las comisiones se suman por encima. Por eso originAmount y
 * totalDebitAmount son cifras distintas y ambas se guardan: la primera es lo prometido
 * al destinatario, la segunda lo que sale de la cuenta virtual.
 *
 * La cotizacion vive 900 segundos exactos (locked_at + 15 min). Un pago aprobado con una
 * cotizacion vencida se ejecutaria a una tasa distinta de la que vio el tesorero, asi que
 * el vencimiento es parte del agregado y no un detalle de la respuesta HTTP.
 */
@Getter
public class Quotation {

    /** TTL documentado de una cotizacion redimible. */
    public static final int TTL_SECONDS = 900;

    private final String id;
    private final TenantId tenantId;
    private final String virtualAccountId;
    private final String recipientId;
    private final QuotationRail rail;
    private final BigDecimal originAmount;
    private final Instant createdAt;

    private FeeBreakdown fees;
    private Instant expiresAt;
    private String kiraQuoteId;
    private BigDecimal destinationAmount;
    private String destinationCurrency;
    private BigDecimal exchangeRate;
    private BigDecimal totalDebitAmount;
    private boolean balanceSufficient;
    private String rateSource;
    private String feesSnapshot;
    private QuotationStatus status;

    public Quotation(String id, TenantId tenantId, String virtualAccountId, String recipientId,
                     QuotationRail rail, BigDecimal originAmount, FeeBreakdown fees, Instant expiresAt) {
        if (originAmount == null || originAmount.signum() <= 0) {
            throw new DomainException("El importe a cotizar debe ser mayor que cero.");
        }
        if (expiresAt == null) {
            throw new DomainException("Una cotizacion sin vencimiento no es una cotizacion.");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.virtualAccountId = virtualAccountId;
        this.recipientId = recipientId;
        this.rail = rail;
        this.originAmount = originAmount;
        this.fees = fees == null ? FeeBreakdown.standard() : fees;
        this.expiresAt = expiresAt;
        this.destinationAmount = originAmount;
        this.exchangeRate = BigDecimal.ONE;
        this.totalDebitAmount = this.fees.totalDebitFor(originAmount);
        this.status = QuotationStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public static Quotation rehydrate(String id, TenantId tenantId, String virtualAccountId,
                                      String recipientId, QuotationRail rail, String kiraQuoteId,
                                      BigDecimal originAmount, BigDecimal destinationAmount,
                                      String destinationCurrency, BigDecimal exchangeRate,
                                      FeeBreakdown fees, BigDecimal totalDebitAmount,
                                      boolean balanceSufficient, String rateSource, String feesSnapshot,
                                      Instant expiresAt, QuotationStatus status, Instant createdAt) {
        Quotation q = new Quotation(id, tenantId, virtualAccountId, recipientId, rail, originAmount,
                fees, expiresAt);
        q.kiraQuoteId = kiraQuoteId;
        q.destinationAmount = destinationAmount == null ? originAmount : destinationAmount;
        q.destinationCurrency = destinationCurrency;
        q.exchangeRate = exchangeRate == null ? BigDecimal.ONE : exchangeRate;
        q.totalDebitAmount = totalDebitAmount == null ? q.totalDebitAmount : totalDebitAmount;
        q.balanceSufficient = balanceSufficient;
        q.rateSource = rateSource;
        q.feesSnapshot = feesSnapshot;
        q.status = status == null ? QuotationStatus.ACTIVE : status;
        return q;
    }

    /**
     * Asienta lo que devolvio POST /v1/quotations.
     *
     * totalDebit es source.amount, el bruto que sale de la cuenta virtual; destination es
     * recipient.amount, el neto que llega. Ninguno se recalcula aqui: el precio que se le
     * muestra al tesorero tiene que ser exactamente el que Kira va a cobrar.
     */
    public void applyKiraQuote(String kiraQuoteId, Instant expiresAt, BigDecimal totalDebit,
                               BigDecimal destinationAmount, String destinationCurrency,
                               BigDecimal exchangeRate, FeeBreakdown fees, boolean balanceSufficient,
                               String rateSource, String feesSnapshot) {
        if (kiraQuoteId == null || kiraQuoteId.isBlank()) {
            // Sin quote_id la cotizacion es un preview y no se puede redimir.
            throw new DomainException("Kira no devolvio una cotizacion redimible.");
        }
        this.kiraQuoteId = kiraQuoteId;
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
        if (totalDebit != null) {
            this.totalDebitAmount = totalDebit;
        }
        if (destinationAmount != null) {
            this.destinationAmount = destinationAmount;
        }
        if (destinationCurrency != null) {
            this.destinationCurrency = destinationCurrency;
        }
        if (exchangeRate != null) {
            this.exchangeRate = exchangeRate;
        }
        if (fees != null) {
            this.fees = fees;
        }
        this.balanceSufficient = balanceSufficient;
        this.rateSource = rateSource;
        this.feesSnapshot = feesSnapshot;
    }

    /**
     * Comprueba que el bruto cuadra con lo prometido mas las comisiones.
     * Un descuadre no es un error de Kira: es que el importe mostrado al tesorero y el
     * debitado de la cuenta no son el mismo numero, y eso hay que verlo.
     */
    public boolean hasConsistentTotals() {
        return totalDebitAmount.compareTo(fees.totalDebitFor(originAmount)) == 0;
    }

    /** La tasa no viene del mercado sino de una politica de contingencia. */
    public boolean usesFallbackRate() {
        return rateSource != null && !"kraken".equalsIgnoreCase(rateSource);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isUsable(Instant now) {
        return status == QuotationStatus.ACTIVE && !isExpired(now);
    }

    public void assertUsable(Instant now) {
        if (status == QuotationStatus.EXECUTED) {
            throw new DomainException("Esta cotizacion ya se ejecuto.");
        }
        if (isExpired(now)) {
            expire();
            throw new DomainException("La cotizacion vencio. Vuelve a cotizar antes de continuar.");
        }
    }

    /** Ademas de vigente, la cuenta debe tener saldo: Kira no encola ni cancela sola. */
    public void assertRedeemable(Instant now) {
        assertUsable(now);
        if (kiraQuoteId == null) {
            throw new DomainException("Esta cotizacion no es redimible.");
        }
        if (!balanceSufficient) {
            throw new DomainException("Saldo insuficiente en la cuenta virtual para este pago.");
        }
    }

    /** Se consume al enviar el pago a Kira, no al prepararlo. */
    public void markExecuted() {
        this.status = QuotationStatus.EXECUTED;
    }

    public void expire() {
        if (status == QuotationStatus.ACTIVE) {
            this.status = QuotationStatus.EXPIRED;
        }
    }

    public long secondsToExpiry(Instant now) {
        return Math.max(0, expiresAt.getEpochSecond() - now.getEpochSecond());
    }
}
