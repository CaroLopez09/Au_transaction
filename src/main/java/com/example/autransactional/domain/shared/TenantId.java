package com.example.autransactional.domain.shared;

import java.util.Objects;

/** Identificador de la organizacion propietaria del dato. Toda consulta debe filtrar por el. */
public record TenantId(String value) {

    /**
     * Organizacion de los operadores de la plataforma (rol SYSTEM). No existe como empresa, asi que
     * ninguna consulta por organizacion devuelve datos para ella: las rutas de empresa quedan vacias
     * por construccion y la consola lee cada organizacion de forma explicita.
     */
    public static final TenantId PLATFORM = new TenantId("__platform__");

    public TenantId {
        Objects.requireNonNull(value, "tenantId no puede ser null");
        if (value.isBlank()) {
            throw new DomainException("tenantId no puede estar vacio");
        }
    }

    public boolean isPlatform() {
        return PLATFORM.value.equals(value);
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
