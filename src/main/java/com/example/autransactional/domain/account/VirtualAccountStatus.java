package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.StatusNormalizer;

/**
 * Estado local de la cuenta virtual, sobre los valores de 2026-06-01:
 * pending, activating, active, failed, deactivated (y frozen).
 *
 * ACTIVE solo sale de 'active', que Kira define como "la cuenta puede recibir depositos".
 * 'activating' es PENDING: el banco aun la esta abriendo. Si llegara un 'approved' de la
 * version anterior tampoco se da por activa; {@link VirtualAccountReadiness} decide.
 */
public enum VirtualAccountStatus {
    PENDING,
    ACTIVE,
    INACTIVE,
    FAILED,
    /** Congelada por un operador de Kira: no mueve fondos mientras dure. */
    FROZEN;

    public static VirtualAccountStatus fromWire(String raw) {
        String normalized = StatusNormalizer.normalize(raw);
        if (normalized == null) {
            return PENDING;
        }
        for (VirtualAccountStatus s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        return switch (normalized) {
            case "ACTIVATED" -> ACTIVE;
            case "DEACTIVATED" -> INACTIVE;
            case "DECLINED", "REJECTED" -> FAILED;
            default -> PENDING;
        };
    }
}
