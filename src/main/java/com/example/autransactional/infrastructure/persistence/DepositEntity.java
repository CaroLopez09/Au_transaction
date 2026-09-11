package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.account.DepositStatus;
import com.example.autransactional.domain.shared.Rail;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** Historial de depositos entrantes. Tabla `deposits`. */
@Entity
@Table(name = "deposits",
        uniqueConstraints = @UniqueConstraint(name = "uk_deposits_kira_id", columnNames = "kira_deposit_id"),
        indexes = @Index(name = "idx_deposits_tenant", columnList = "tenant_id"))
@Getter
@Setter
@NoArgsConstructor
public class DepositEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "virtual_account_id", nullable = false, length = 36)
    private String virtualAccountId;

    @Column(name = "kira_deposit_id", length = 100)
    private String kiraDepositId;

    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal grossAmount;

    @Column(name = "fee_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal netAmount;

    @Column(nullable = false, length = 10)
    private String currency = "USD";

    @Column(name = "sender_name", length = 255)
    private String senderName;

    @Column(name = "sender_account", length = 100)
    private String senderAccount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 50)
    private Rail rail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private DepositStatus status = DepositStatus.COMPLETED;

    /** Deposito de verificacion de cuenta: no es un ingreso real y no suma saldo. */
    @Column(nullable = false)
    private boolean microdeposit = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
