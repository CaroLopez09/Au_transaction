package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;

/**
 * Usuario humano de una empresa cliente.
 * No confundir con el "user" de Kira, que es la empresa misma en el KYB.
 */
public record OperatorUser(String id, TenantId tenantId, String email, String passwordHash,
                           String firstName, String lastName, Role role, UserStatus status,
                           String mfaSecret, boolean mfaEnabled) {

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
