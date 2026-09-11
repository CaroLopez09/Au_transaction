package com.example.autransactional.infrastructure.security;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;

/**
 * Contexto de la organizacion activa para el hilo que atiende la peticion.
 * Regla de oro: siempre limpiar en un finally, o el siguiente request reutiliza el hilo
 * del pool y hereda el tenant equivocado.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantId tenantId) {
        CURRENT.set(tenantId);
    }

    public static TenantId get() {
        return CURRENT.get();
    }

    public static TenantId require() {
        TenantId t = CURRENT.get();
        if (t == null) {
            throw new DomainException("No hay organizacion en contexto para esta peticion.");
        }
        return t;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
