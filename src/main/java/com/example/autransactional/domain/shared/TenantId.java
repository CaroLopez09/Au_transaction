package com.example.autransactional.domain.shared;

import java.util.Objects;

/** Identificador de la organizacion propietaria del dato. Toda consulta debe filtrar por el. */
public record TenantId(String value) {
    public TenantId {
        Objects.requireNonNull(value, "tenantId no puede ser null");
        if (value.isBlank()) {
            throw new DomainException("tenantId no puede estar vacio");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
