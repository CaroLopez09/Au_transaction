package com.example.autransactional.domain.shared;

import java.util.UUID;

/**
 * Clave de idempotencia. Kira la exige como UUID v4 en POST /v1/users, /v1/recipients,
 * /v1/virtual-accounts y /v1/virtual-accounts/{id}/payout.
 * Regla: una clave nueva por intencion distinta; la misma solo para reintentar la misma intencion.
 */
public record IdempotencyKey(String value) {
    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new DomainException("La clave de idempotencia no puede estar vacia");
        }
    }

    public static IdempotencyKey newKey() {
        return new IdempotencyKey(UUID.randomUUID().toString());
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
