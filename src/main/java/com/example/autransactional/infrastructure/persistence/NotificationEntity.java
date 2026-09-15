package com.example.autransactional.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Avisos de negocio por organizacion. Tabla `notifications`. */
@Entity
@Table(name = "notifications",
        indexes = @Index(name = "idx_notifications_tenant", columnList = "tenant_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class NotificationEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(nullable = false, length = 60)
    private String kind;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(length = 500)
    private String message;

    @Column(name = "resource_type", length = 40)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
