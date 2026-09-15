package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.treasury.PayoutApprovalState;
import com.example.autransactional.domain.treasury.PayoutStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pagos con segregacion maker-checker. Tabla `payouts`.
 *
 * Las columnas approval_state, quotation_expires_at, rejection_reason y error_code no
 * existen en la API de Kira: son el control interno del BFF, que es justamente lo que
 * Kira no ofrece a los integradores.
 */
@Entity
@Table(name = "payouts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payouts_idempotency", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uk_payouts_kira_id", columnNames = "kira_payout_id")
        },
        indexes = {
                @Index(name = "idx_payouts_tenant", columnList = "tenant_id, created_at"),
                @Index(name = "idx_payouts_idempotency", columnList = "idempotency_key")
        })
@Getter
@Setter
@NoArgsConstructor
public class PayoutEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "kira_user_id", length = 100)
    private String kiraUserId;

    @Column(name = "virtual_account_id", nullable = false, length = 36)
    private String virtualAccountId;

    @Column(name = "recipient_id", nullable = false, length = 36)
    private String recipientId;

    /**
     * Nulo mientras el pago es un borrador. El DDL de referencia lo declara NOT NULL;
     * aqui se permite nulo porque el operador prepara la orden antes de cotizar, y el
     * envio a Kira si exige cotizacion vigente.
     */
    @Column(name = "quotation_id", length = 36)
    private String quotationId;

    @Column(name = "kira_payout_id", length = 100)
    private String kiraPayoutId;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "kira_fee", nullable = false, precision = 18, scale = 4)
    private BigDecimal kiraFee;

    @Column(name = "platform_fee", nullable = false, precision = 18, scale = 4)
    private BigDecimal platformFee;

    @Column(name = "total_fee", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalFee;

    @Column(nullable = false, length = 10)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private PayoutStatus status;

    /** UUID v4 obligatorio para Kira. */
    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    /** Operador (tesoreria_maker) que creo la solicitud. */
    @Column(name = "maker_user_id", nullable = false, length = 36)
    private String makerUserId;

    /** Tesorero (tesoreria_approver) que autorizo la ejecucion. */
    @Column(name = "approver_user_id", length = 36)
    private String approverUserId;

    /** Primera de dos firmas cuando el monto supera el umbral de la empresa. */
    @Column(name = "first_approver_user_id", length = 36)
    private String firstApproverUserId;

    // --- Control interno del BFF, ajeno a la API de Kira ---

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "approval_state", nullable = false, length = 50)
    private PayoutApprovalState approvalState;

    @Column(name = "quotation_expires_at")
    private Instant quotationExpiresAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "error_code", length = 120)
    private String errorCode;

    /** IMAD / ACH trace / UETR. Es el comprobante que el cliente final reclama. */
    @Column(name = "reference_number", length = 120)
    private String referenceNumber;

    @Column(name = "payment_method", length = 20)
    private String paymentMethod;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
