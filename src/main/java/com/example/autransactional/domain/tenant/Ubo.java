package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Beneficiario final, director o firmante de la empresa cliente.
 *
 * Existe como tabla propia por dos motivos que la API impone: hay que reconstruir el array
 * 'associated_persons' COMPLETO en cada PUT (un PUT parcial borra campos en silencio), y
 * el resultado real del liveness solo llega una vez, por webhook.
 *
 * El enlace de liveness que emite Kira vive 7 dias: por eso la fecha de vencimiento se
 * guarda aparte del estado. Un enlace vencido no se reintenta, se vuelve a pedir.
 */
@Getter
public class Ubo {

    /** Rol por defecto cuando el formulario de onboarding no lo precisa. */
    public static final String DEFAULT_ROLE = "Beneficiario Final";

    /** Umbral a partir del cual Kira considera beneficiario final a una persona. */
    public static final BigDecimal BENEFICIAL_OWNER_THRESHOLD = new BigDecimal("5");

    private final String id;
    private final TenantId tenantId;
    private Instant createdAt;

    private String personReferenceId;
    private String firstName;
    private String lastName;
    private String documentType;
    private String documentNumber;
    private BigDecimal ownershipPercentage;
    private String roleInCompany;

    // --- Campos que Kira exige en associated_persons[] y que causan bloqueos silenciosos ---

    private boolean hasOwnership;
    private boolean hasControl;
    private boolean signer;
    private boolean politicallyExposed;
    private String countryOfBirth;

    private LivenessStatus livenessStatus;
    private String livenessLink;
    private Instant livenessExpiresAt;
    private Instant updatedAt;

    public Ubo(String id, TenantId tenantId, String firstName, String lastName,
               BigDecimal ownershipPercentage, String roleInCompany) {
        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
            throw new DomainException("Todo beneficiario final necesita nombre y apellido.");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.ownershipPercentage = normalizePercentage(ownershipPercentage);
        this.roleInCompany = roleInCompany == null || roleInCompany.isBlank() ? DEFAULT_ROLE : roleInCompany;
        this.livenessStatus = LivenessStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Ubo rehydrate(String id, TenantId tenantId, String personReferenceId,
                                String firstName, String lastName, String documentType,
                                String documentNumber, BigDecimal ownershipPercentage,
                                String roleInCompany, boolean hasOwnership, boolean hasControl,
                                boolean signer, boolean politicallyExposed, String countryOfBirth,
                                LivenessStatus livenessStatus, String livenessLink,
                                Instant livenessExpiresAt, Instant createdAt, Instant updatedAt) {
        Ubo u = new Ubo(id, tenantId, firstName, lastName, ownershipPercentage, roleInCompany);
        // Sin esto, cada lectura "reiniciaba" la fecha de alta con la hora actual.
        u.createdAt = createdAt == null ? u.createdAt : createdAt;
        u.personReferenceId = personReferenceId;
        u.documentType = documentType;
        u.documentNumber = documentNumber;
        u.hasOwnership = hasOwnership;
        u.hasControl = hasControl;
        u.signer = signer;
        u.politicallyExposed = politicallyExposed;
        u.countryOfBirth = countryOfBirth;
        u.livenessStatus = livenessStatus == null ? LivenessStatus.PENDING : livenessStatus;
        u.livenessLink = livenessLink;
        u.livenessExpiresAt = livenessExpiresAt;
        u.updatedAt = updatedAt;
        return u;
    }

    public void describeDocument(String documentType, String documentNumber) {
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        touch();
    }

    /**
     * Define el papel de la persona en el KYB.
     *
     * hasOwnership es un booleano explicito y no se deduce del cargo: el titulo NO
     * identifica al beneficiario, y omitirlo deja el KYB bloqueado sin decir por que.
     */
    public void describeRole(boolean hasOwnership, BigDecimal ownershipPercentage, boolean hasControl,
                             boolean signer, boolean politicallyExposed, String countryOfBirth) {
        if (countryOfBirth == null || countryOfBirth.isBlank()) {
            // Kira no admite vacio en country_of_birth (ISO-3).
            throw new DomainException("El pais de nacimiento es obligatorio (codigo ISO-3).");
        }
        this.hasOwnership = hasOwnership;
        this.ownershipPercentage = normalizePercentage(ownershipPercentage);
        this.hasControl = hasControl;
        this.signer = signer;
        this.politicallyExposed = politicallyExposed;
        this.countryOfBirth = countryOfBirth.trim().toUpperCase(java.util.Locale.ROOT);
        touch();
    }

    /** Kira exige al menos una persona asi para verificar a la empresa. */
    public boolean isBeneficialOwner() {
        return hasOwnership && ownershipPercentage.compareTo(BENEFICIAL_OWNER_THRESHOLD) >= 0;
    }

    public void linkKiraPerson(String personReferenceId) {
        this.personReferenceId = personReferenceId;
        touch();
    }

    /** Se invoca con la respuesta de POST /v1/users/{id}/liveness-link. */
    public void assignLivenessLink(String link, Instant expiresAt) {
        if (link == null || link.isBlank()) {
            throw new DomainException("Kira no devolvio enlace de prueba de vida.");
        }
        this.livenessLink = link;
        this.livenessExpiresAt = expiresAt;
        this.livenessStatus = LivenessStatus.PENDING;
        touch();
    }

    public boolean isLivenessLinkExpired(Instant now) {
        return livenessExpiresAt != null && !now.isBefore(livenessExpiresAt);
    }

    /** No retrocede desde un estado final: los eventos llegan una vez y sin orden garantizado. */
    public void applyLivenessStatus(LivenessStatus incoming) {
        if (incoming == null || (livenessStatus.isFinal() && !incoming.isFinal())) {
            return;
        }
        this.livenessStatus = incoming;
        touch();
    }

    public void expireLivenessLink() {
        if (!livenessStatus.isFinal()) {
            this.livenessStatus = LivenessStatus.EXPIRED;
            touch();
        }
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    private static BigDecimal normalizePercentage(BigDecimal raw) {
        BigDecimal value = raw == null ? BigDecimal.ZERO : raw;
        if (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new DomainException("El porcentaje de propiedad debe estar entre 0 y 100.");
        }
        return value;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
