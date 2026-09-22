package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;

import java.util.Locale;

/**
 * Interruptores por tenant (arquitectura §8): activacion gradual de un modulo completo, a
 * diferencia de {@link TenantSettings#enabledRails()}/{@link TenantSettings#enabledTokens()}
 * que controlan que riel/token puede usarse. Aqui se apaga el modulo entero.
 */
public enum FeatureFlag {
    /** Sin esto, pedir liveness (SyncUbosService.requestLivenessLinks) queda bloqueado. */
    LIVENESS,
    /** Sin esto, sincronizar/objetar RFIs con Kira (AnswerRfiService.sync) queda bloqueado. */
    RFIS;

    public static FeatureFlag from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException("El feature flag es obligatorio.");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException("Feature flag no soportado: " + raw);
        }
    }
}
