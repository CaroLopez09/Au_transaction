package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.tenant.TenantStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Empresas clientes: Juriscop, Bankvision, AU Colombia. Tabla `tenants` del esquema v2. */
@Entity
@Table(name = "tenants",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenants_name", columnNames = "name"),
                @UniqueConstraint(name = "uk_tenants_kira_user", columnNames = "kira_user_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class TenantEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    /** NIT / identificador fiscal. */
    @Column(name = "tax_id", nullable = false, length = 50)
    private String taxId;

    @Column(nullable = false, length = 100)
    private String jurisdiction = "Colombia";

    /** Id devuelto por POST /v1/users de Kira. */
    @Column(name = "kira_user_id", length = 100)
    private String kiraUserId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private TenantStatus status = TenantStatus.CREATED;

    /** Array de productos bancarios habilitados por Kira, con su elegibilidad. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eligible_products")
    private String eligibleProducts;

    // --- Estado del bucle de onboarding. Columnas propias del BFF: cada una cubre un
    // hueco concreto de la API de Kira, documentado en el campo correspondiente. ---

    /** Fuente de verdad del formulario de KYB: lo que Kira sigue exigiendo, por producto. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_fields")
    private String missingFields;

    /** El GET no lo devuelve; sin este dato, pedir los enlaces de liveness da 422. */
    @Column(name = "verification_triggered", nullable = false)
    private boolean verificationTriggered = false;

    /**
     * Objeto completo enviado a Kira. El GET no devuelve el cuestionario y un PUT parcial
     * borra en silencio lo que no viaje en el: sin esta copia, el siguiente PUT es una
     * perdida de datos garantizada.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "onboarding_payload")
    private String onboardingPayload;

    /** Se persiste ANTES del primer POST /v1/users: un reintento no debe crear dos empresas. */
    @Column(name = "onboarding_idempotency_key", length = 255)
    private String onboardingIdempotencyKey;

    /** Unica fuente del motivo: llega solo por el webhook user.verification.failed. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
