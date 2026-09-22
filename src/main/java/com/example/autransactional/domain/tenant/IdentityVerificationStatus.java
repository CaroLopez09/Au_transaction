package com.example.autransactional.domain.tenant;

/** Estado de la identidad personal de un operador de una empresa cliente. */
public enum IdentityVerificationStatus {
    PENDING_DOCUMENTS,
    PENDING_LIVENESS,
    IN_REVIEW,
    VERIFIED,
    REJECTED,
    EXPIRED;

    public boolean isVerified() {
        return this == VERIFIED;
    }
}