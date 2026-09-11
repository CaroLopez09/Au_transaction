package com.example.autransactional.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Bitacora de auditoria B2B. Tabla `audit_logs`.
 * Kira no ofrece historial de cambios al integrador, asi que el registro es propio.
 */
@Entity
@Table(name = "audit_logs",
        indexes = @Index(name = "idx_audit_tenant", columnList = "tenant_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class AuditLogEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    @Column(name = "user_id", length = 36)
    private String userId;

    /** tesoreria_maker, tesoreria_approver, compliance_internal, admin, read_only. */
    @Column(name = "user_role", length = 100)
    private String userRole;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    /** Detalle del cambio en JSON. Nunca secretos, biometria ni datos personales. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes")
    private String changes;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
