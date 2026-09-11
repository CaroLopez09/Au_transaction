package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.StatusNormalizer;

/** Estado del enlace biometrico alojado que Kira emite para cada UBO. */
public enum LivenessStatus {
    PENDING,
    COMPLETED,
    EXPIRED,
    FAILED;

    public static LivenessStatus fromWire(String raw) {
        String normalized = StatusNormalizer.normalize(raw);
        if (normalized == null) {
            return PENDING;
        }
        for (LivenessStatus s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        return switch (normalized) {
            case "APPROVED", "PASSED", "SUCCESS" -> COMPLETED;
            case "DECLINED", "REJECTED" -> FAILED;
            default -> PENDING;
        };
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
