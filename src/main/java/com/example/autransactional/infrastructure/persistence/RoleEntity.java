package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.tenant.RoleScope;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Catalogo RBAC de la plataforma. La fila es la autoridad para la FK de `users`;
 * el enum {@link com.example.autransactional.domain.tenant.Role} es la autoridad
 * para las reglas de negocio y para @PreAuthorize.
 */
@Entity
@Table(name = "roles",
        uniqueConstraints = @UniqueConstraint(name = "uk_roles_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
public class RoleEntity {

    @Id
    @Column(length = 36)
    private String id;

    /** tesoreria_maker, tesoreria_approver, compliance_internal, admin, read_only. */
    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "scope", nullable = false, length = 50)
    private RoleScope scope = RoleScope.TENANT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
