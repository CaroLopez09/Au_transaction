package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.domain.tenant.IdentityVerificationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Usuarios de cada empresa cliente. Tabla `users` del esquema v2. */
@Entity
@Table(name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
@Getter
@Setter
@NoArgsConstructor
public class OperatorUserEntity {

    @Id
    @Column(length = 36)
    private String id;

    /** NULL cuando el usuario es de soporte de la plataforma (rol de scope 'system'). */
    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    // EAGER a proposito: el rol se necesita en cada verificacion de permisos y las
    // lecturas salen de la transaccion antes de que nadie lo consulte.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_users_role"))
    private RoleEntity role;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private UserStatus status = UserStatus.ACTIVE;

    /** Secreto TOTP cifrado con AES-GCM (MfaSecretCipher). */
    @Column(name = "mfa_secret", length = 255)
    private String mfaSecret;

    /** Avisos creados despues de este instante cuentan como no leidos para este usuario. */
    @Column(name = "notifications_seen_at")
    private Instant notificationsSeenAt;

    /** false mientras el secreto esta pendiente de confirmar con un primer codigo. */
    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

        /** Estado de documento y rostro, independiente de la activacion de la cuenta. */
        @Enumerated(EnumType.STRING)
        @JdbcTypeCode(SqlTypes.VARCHAR)
        @Column(name = "identity_status", nullable = false, length = 50)
        private IdentityVerificationStatus identityStatus = IdentityVerificationStatus.PENDING_DOCUMENTS;

        /** Referencia que Kira entrega para correlacionar el webhook de liveness. */
        @Column(name = "kira_person_reference_id", length = 100)
        private String kiraPersonReferenceId;

        @Column(name = "identity_document_type", length = 100)
        private String identityDocumentType;

        /** Solo los ultimos cuatro caracteres; el numero completo no se almacena en el BFF. */
        @Column(name = "identity_document_last_four", length = 4)
        private String identityDocumentLastFour;

        @Column(name = "identity_issuing_country", length = 3)
        private String identityIssuingCountry;

        @Column(name = "biometric_consent_at")
        private Instant biometricConsentAt;

        @Column(name = "identity_requested_at")
        private Instant identityRequestedAt;

        @Column(name = "identity_verified_at")
        private Instant identityVerifiedAt;

        @Column(name = "identity_rejection_reason", length = 500)
        private String identityRejectionReason;

        /** Intentos consecutivos rechazados por el proveedor (rostro/documento no coinciden). */
        @Column(name = "identity_rejected_attempts", nullable = false)
        private int identityRejectedAttempts = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
