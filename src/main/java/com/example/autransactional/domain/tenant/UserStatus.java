package com.example.autransactional.domain.tenant;

/** Estado del usuario dentro de la empresa cliente. */
public enum UserStatus {
    PENDING_IDENTITY,
    ACTIVE,
    SUSPENDED,
    DISABLED;

    public boolean canLogin() {
        return this == ACTIVE;
    }
}
