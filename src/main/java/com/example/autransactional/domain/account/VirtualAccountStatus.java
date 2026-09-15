package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.StatusNormalizer;

/**
 * Estado local de la cuenta virtual.
 *
 * Ojo: en el pin 2026-04-14 la API colapsa activating/active en "approved", asi que
 * ACTIVE aqui NO implica que la cuenta pueda mover fondos. Esa pregunta la responde
 * {@link VirtualAccountReadiness}, no este enum.
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
            case "APPROVED", "ACTIVATING", "ACTIVATED" -> ACTIVE;
            case "DEACTIVATED" -> INACTIVE;
            case "DECLINED", "REJECTED" -> FAILED;
            default -> PENDING;
        };
    }
}
