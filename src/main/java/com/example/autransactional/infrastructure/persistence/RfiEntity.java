package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.compliance.RfiStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Solicitudes de informacion de compliance. Tabla `rfis`. */
@Entity
@Table(name = "rfis",
        uniqueConstraints = @UniqueConstraint(name = "uk_rfis_kira_id", columnNames = "kira_rfi_id"),
        indexes = {
                @Index(name = "idx_rfis_tenant", columnList = "tenant_id"),
                @Index(name = "idx_rfis_blocking", columnList = "blocking_resource_id")})
@Getter
@Setter
@NoArgsConstructor
public class RfiEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "kira_rfi_id", length = 100)
    private String kiraRfiId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private RfiStatus status = RfiStatus.PENDING;

    /** Array de items requeridos, tal como lo entrega Kira. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_payload", nullable = false)
    private String itemsPayload;

    @Column(name = "due_date")
    private Instant dueDate;

    /** blocking.type de Kira ("transfer", ...). Desviacion del DDL v2: enlaza el RFI con lo que detiene. */
    @Column(name = "blocking_type", length = 30)
    private String blockingType;

    /** blocking.transfer_uuid: identificador de Kira del recurso bloqueado. */
    @Column(name = "blocking_resource_id", length = 100)
    private String blockingResourceId;

    @Column(name = "resolution_reason", length = 20)
    private String resolutionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
