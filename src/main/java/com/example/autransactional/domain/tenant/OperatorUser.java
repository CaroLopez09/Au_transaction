package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;

/**
 * Usuario humano de una empresa cliente.
 * No confundir con el "user" de Kira, que es la empresa misma en el KYB.
 */
public record OperatorUser(String id, TenantId tenantId, String email, String passwordHash,
                           String firstName, String lastName, Role role, UserStatus status,
                           String mfaSecret, boolean mfaEnabled, OperatorIdentity identity,
                           boolean mustChangePassword, boolean passwordResetByAdmin) {

    /**
     * Compatibilidad para llamadas existentes que aun no conocen la contrasena temporal:
     * asume que no hay cambio obligatorio pendiente ni reset de administrador en curso.
     */
    public OperatorUser(String id, TenantId tenantId, String email, String passwordHash,
                        String firstName, String lastName, Role role, UserStatus status,
                        String mfaSecret, boolean mfaEnabled, OperatorIdentity identity) {
        this(id, tenantId, email, passwordHash, firstName, lastName, role, status, mfaSecret,
                mfaEnabled, identity, false, false);
    }

    /** Compatibilidad para operadores ya creados: su identidad queda pendiente de documentacion. */
    public OperatorUser(String id, TenantId tenantId, String email, String passwordHash,
                        String firstName, String lastName, Role role, UserStatus status,
                        String mfaSecret, boolean mfaEnabled) {
        this(id, tenantId, email, passwordHash, firstName, lastName, role, status, mfaSecret,
                mfaEnabled, OperatorIdentity.pendingDocuments());
    }

    public void assertCanLogin() {
        if (!status.canLogin()) {
            throw new DomainException("La cuenta esta desactivada.");
        }
    }

    public void assertBelongsTo(TenantId expected) {
        if (!tenantId.equals(expected)) {
            throw new DomainException("El recurso pertenece a otra organizacion.");
        }
    }

    public String fullName() {
        return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
    }

    public boolean isActive() {
        return status.canLogin();
    }
}
