package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.account.VirtualAccountMode;
import com.example.autransactional.domain.account.VirtualAccountStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** Cuentas virtuales B2B. Tabla `virtual_accounts`. */
@Entity
@Table(name = "virtual_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_va_kira_account", columnNames = "kira_account_id"),
        indexes = @Index(name = "idx_virtual_accounts_tenant", columnList = "tenant_id"))
@Getter
@Setter
@NoArgsConstructor
public class VirtualAccountEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "kira_account_id", length = 100)
    private String kiraAccountId;

    @Column(name = "bank_name", length = 150)
    private String bankName;

    @Column(name = "account_number", length = 100)
    private String accountNumber;

    @Column(name = "routing_number", length = 100)
    private String routingNumber;

    @Column(nullable = false, length = 10)
    private String currency = "USD";

    /** fiat o crypto. INMUTABLE tras crearse: cambiarlo obliga a abrir otra cuenta. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private VirtualAccountMode mode = VirtualAccountMode.FIAT;

    /** Banco con el que se abrio (kira.bank): jp_morgan o austin_capital_trust. */
    @Column(length = 60)
    private String bank;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private VirtualAccountStatus status = VirtualAccountStatus.PENDING;

    @Column(name = "balance_available", nullable = false, precision = 18, scale = 4)
    private BigDecimal balanceAvailable = BigDecimal.ZERO;

    /**
     * Columna propia del BFF: el evento virtual_account.activated es la senal fondos-listos, y
     * se recuerda haberlo visto aunque una consulta posterior tarde en reflejar 'active'.
     */
    @Column(name = "activated_event_seen", nullable = false)
    private boolean activatedEventSeen = false;

    @Column(name = "balance_refreshed_at")
    private Instant balanceRefreshedAt;

    /** Se persiste ANTES del primer POST: un reintento no debe abrir dos cuentas. */
    @Column(name = "opening_idempotency_key", length = 255)
    private String openingIdempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
