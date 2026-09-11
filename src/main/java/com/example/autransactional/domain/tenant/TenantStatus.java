package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.StatusNormalizer;

/**
 * Estado del KYB de la empresa cliente en Kira.
 * Se recibe por evento user.* y por GET /v1/users/{id}; se compara siempre sin
 * distinguir mayusculas y un valor desconocido no rompe la maquina.
 */
public enum TenantStatus {
    CREATED,
    VERIFYING,
    REVIEW,
    VERIFIED,
    REJECTED;

    public static TenantStatus fromWire(String raw) {
        String normalized = StatusNormalizer.normalize(raw);
        if (normalized == null) {
            return CREATED;
        }
        for (TenantStatus s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        // Vocabulario alterno visto en la API: approved/declined sobre el mismo recurso.
        return switch (normalized) {
            case "APPROVED" -> VERIFIED;
            case "DECLINED" -> REJECTED;
            case "PENDING", "IN_REVIEW" -> REVIEW;
            default -> CREATED;
        };
    }

    public boolean canOperate() {
        return this != REJECTED;
    }
}
