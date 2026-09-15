package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.tenant.UserStatus;
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
