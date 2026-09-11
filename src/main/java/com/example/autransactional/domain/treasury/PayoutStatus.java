package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.StatusNormalizer;
import java.util.Set;

/**
 * Estado del payout en Kira (vocabulario del recurso, en MAYUSCULAS segun GET /v1/payouts/{id}).
 * KYT_PENDING e IN_REVIEW solo afloran via el evento payout.status_changed y son NO terminales.
 * No existe RETURNED ni CANCELLED como estado de recurso: ambos resuelven en FAILED.
 */
public enum PayoutStatus {
    NOT_SUBMITTED,
    CREATED,
    PENDING,
    PROCESSING,
    KYT_PENDING,
    IN_REVIEW,
    COMPLETED,
    FAILED,
    EXPIRED,
    UNKNOWN;

    private static final Set<PayoutStatus> TERMINAL = Set.of(COMPLETED, FAILED, EXPIRED);

    /** Tolerante: un estado desconocido no rompe la maquina, se trata como no terminal. */
    public static PayoutStatus fromWire(String raw) {
        String normalized = StatusNormalizer.normalize(raw);
        if (normalized == null) {
            return UNKNOWN;
        }
        // Un evento payout.returned llega con data.status "returned" y el recurso resuelve en FAILED.
        if ("RETURNED".equals(normalized) || "CANCELLED".equals(normalized) || "CANCELED".equals(normalized)) {
            return FAILED;
        }
        for (PayoutStatus s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        return UNKNOWN;
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isInFlight() {
        return this == CREATED || this == PENDING || this == PROCESSING
                || this == KYT_PENDING || this == IN_REVIEW;
    }
}
