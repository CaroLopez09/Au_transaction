package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.treasury.BankAccountKind;
import com.example.autransactional.domain.treasury.RecipientStatus;
import com.example.autransactional.domain.treasury.WalletToken;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Directorio de destinatarios de pagos. Tabla `recipients`.
 *
 * El espejo es completo a proposito. Kira no expone actualizacion ni borrado, asi que
 * corregir un destinatario obliga a reconstruir el alta entera; y ademas devuelve
 * bank_address.state y postal_code VACIOS aunque se hayan enviado. Sin esta copia, esos
 * datos se pierden en cuanto se guardan.
 */
@Entity
@Table(name = "recipients",
        uniqueConstraints = @UniqueConstraint(name = "uk_recipients_kira_id", columnNames = "kira_recipient_id"),
        indexes = @Index(name = "idx_recipients_tenant", columnList = "tenant_id"))
@Getter
@Setter
@NoArgsConstructor
public class RecipientEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    /** La respuesta de Kira lo llama recipient_id, no id. */
    @Column(name = "kira_recipient_id", length = 100)
    private String kiraRecipientId;

    /** Alias legible: razon social o nombre completo, segun el tipo de titular. */
    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private Rail rail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private RecipientStatus status = RecipientStatus.ACTIVE;

    /** Reemplazo logico: apunta al destinatario que corrige a este. */
    @Column(name = "replaced_by_recipient_id", length = 36)
    private String replacedByRecipientId;

    /** Operador que lo registro (segregacion de funciones al aprobar pagos). */
    @Column(name = "created_by_user_id", length = 36)
    private String createdByUserId;

    // --- Titular ---

    @Column(name = "is_business", nullable = false)
    private boolean business = false;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(length = 255)
    private String email;

    @Column(length = 16)
    private String phone;

    /** Direccion del titular. Pais en ISO-2, a diferencia del KYB de la empresa. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "holder_address")
    private String holderAddress;

    // --- Cuenta: solo se rellena el bloque del riel correspondiente ---

    @Column(name = "bank_name", length = 150)
    private String bankName;

    @Column(name = "account_number", length = 100)
    private String accountNumber;

    @Column(name = "routing_number", length = 20)
    private String routingNumber;

    @Column(name = "swift_code", length = 20)
    private String swiftCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_kind", length = 20)
    private BankAccountKind accountKind;

    /** ACH: la direccion del banco viaja como texto plano. */
    @Column(name = "bank_address_text", length = 500)
    private String bankAddressText;

    /**
     * WIRE: la direccion del banco viaja como objeto. Se guarda entera porque Kira
     * devuelve state y postal_code vacios aunque se hayan enviado.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bank_address")
    private String bankAddress;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "wallet_token", length = 20)
    private WalletToken walletToken;

    /** solana, polygon o tron. Determina el riel de la cotizacion. */
    @Column(length = 20)
    private String network;

    @Column(name = "wallet_address", length = 255)
    private String walletAddress;

    @Column(name = "doc_type", length = 50)
    private String docType;

    @Column(name = "doc_number", length = 100)
    private String docNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
