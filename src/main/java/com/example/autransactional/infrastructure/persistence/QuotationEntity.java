package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.treasury.QuotationRail;
import com.example.autransactional.domain.treasury.QuotationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cotizaciones con desglose comisional 15 USD (Kira) + 15 USD (plataforma) = 30 USD.
 * Tabla `quotations`.
 */
@Entity
@Table(name = "quotations",
        indexes = @Index(name = "idx_quotations_tenant", columnList = "tenant_id"))
@Getter
@Setter
@NoArgsConstructor
public class QuotationEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "virtual_account_id", nullable = false, length = 36)
    private String virtualAccountId;

    @Column(name = "recipient_id", nullable = false, length = 36)
    private String recipientId;

    @Column(name = "kira_quote_id", length = 100)
    private String kiraQuoteId;

    /** Riel cotizado. Debe corresponder al account_type del destinatario. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private QuotationRail rail;

    @Column(name = "origin_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal originAmount;

    @Column(name = "destination_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal destinationAmount;

    @Column(name = "exchange_rate", precision = 18, scale = 6)
    private BigDecimal exchangeRate = BigDecimal.ONE;

    @Column(name = "kira_fee", nullable = false, precision = 18, scale = 4)
    private BigDecimal kiraFee;

    @Column(name = "platform_fee", nullable = false, precision = 18, scale = 4)
    private BigDecimal platformFee;

    @Column(name = "total_fee", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalFee;

    /** origin_amount + total_fee. */
    @Column(name = "total_debit_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalDebitAmount;

    @Column(name = "destination_currency", length = 10)
    private String destinationCurrency;

    /** Se valida antes de habilitar el pago: Kira no encola ni cancela por saldo. */
    @Column(name = "balance_sufficient", nullable = false)
    private boolean balanceSufficient = false;

    /** kraken, fallback_at_peg, stale_at_peg... Si no es kraken, es tasa de contingencia. */
    @Column(name = "rate_source", length = 50)
    private String rateSource;

    /**
     * Copia de fees[] y totals tal como los devolvio Kira. Es la unica prueba de que el
     * precio mostrado al tesorero es el que se cobro.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fees_snapshot")
    private String feesSnapshot;

    /** TTL de 15 minutos devuelto por Kira. */
    @Column(name = "quote_expires_at", nullable = false)
    private Instant quoteExpiresAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private QuotationStatus status = QuotationStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
