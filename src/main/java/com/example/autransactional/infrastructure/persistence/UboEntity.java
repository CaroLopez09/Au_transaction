package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.tenant.LivenessStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** Beneficiarios finales, directores y liveness por empresa. Tabla `ubos`. */
@Entity
@Table(name = "ubos", indexes = @Index(name = "idx_ubos_tenant", columnList = "tenant_id"))
@Getter
@Setter
@NoArgsConstructor
public class UboEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    /** Referencia del sujeto en Kira. */
    @Column(name = "person_reference_id", length = 100)
    private String personReferenceId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "document_type", length = 50)
    private String documentType;

    @Column(name = "document_number", length = 100)
    private String documentNumber;

    @Column(name = "ownership_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal ownershipPercentage = BigDecimal.ZERO;

    /** Director, Accionista, Firmante, Beneficiario Final. Etiqueta legible, no regla. */
    @Column(name = "role_in_company", length = 100)
    private String roleInCompany;

    // --- Campos exigidos por associated_persons[]. El cargo NO identifica al
    // beneficiario: Kira mira estos booleanos explicitos. ---

    @Column(name = "has_ownership", nullable = false)
    private boolean hasOwnership = false;

    @Column(name = "has_control", nullable = false)
    private boolean hasControl = false;

    @Column(name = "is_signer", nullable = false)
    private boolean signer = false;

    /** pep_status: obligatorio para Kira, sin excepciones. */
    @Column(name = "politically_exposed", nullable = false)
    private boolean politicallyExposed = false;

    /** ISO-3. Kira no admite vacio. */
    @Column(name = "country_of_birth", length = 3)
    private String countryOfBirth;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "liveness_status", nullable = false, length = 50)
    private LivenessStatus livenessStatus = LivenessStatus.PENDING;

    /**
     * Enlace de prueba biometrica alojada. Vigencia de 7 dias.
     * TEXT y no VARCHAR(255): la URL viene firmada y desborda el tamano por defecto.
     */
    @Lob
    @Column(name = "liveness_link", length = 65535)
    private String livenessLink;

    @Column(name = "liveness_expires_at")
    private Instant livenessExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
