package com.example.autransactional.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Bitacora inmutable de eventos de Kira. Tabla `webhooks_log`.
 *
 * La unicidad de event_id es lo que hace idempotente el procesamiento: Kira entrega una
 * sola vez y sin reintento, y el mismo evento puede llegar por dos familias distintas
 * (payout.* y payout.status_changed).
 */
@Entity
@Table(name = "webhooks_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_webhooks_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_webhooks_event", columnList = "event_id"))
@Getter
@Setter
@NoArgsConstructor
public class WebhookEventEntity {

    @Id
    @Column(length = 36)
    private String id;

    /** data.event_id. Nunca existe a nivel raiz en ninguna de las dos envolturas. */
    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(nullable = false)
    private boolean processed = false;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // --- Proyeccion propia del BFF: permite reconciliar sin reparsear el payload ---

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Column(name = "normalized_status", length = 50)
    private String normalizedStatus;

    @Column(name = "processing_error", length = 1000)
    private String processingError;
}
