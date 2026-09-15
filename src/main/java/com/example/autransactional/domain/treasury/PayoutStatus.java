package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.StatusNormalizer;
import java.util.Set;

/**
 * Estado del payout en Kira (vocabulario del recurso, en MAYUSCULAS segun GET /v1/payouts/{id}).
 * KYT_PENDING e IN_REVIEW solo afloran via el evento payout.status_changed y son NO terminales.
 * CANCELLED (detenido antes de enviarse) es un estado final propio. RETURNED no existe como
 * estado: una devolucion bancaria pasa el pago a FAILED, incluso desde COMPLETED.
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
    CANCELLED,
    EXPIRED,
    UNKNOWN;

    /**
     * COMPLETED esta aqui para que el reconciliador deje de consultarlo, pero para Kira no es
     * final: una devolucion posterior llega por payout.returned y lo pasa a FAILED.
     */
    private static final Set<PayoutStatus> TERMINAL = Set.of(COMPLETED, FAILED, CANCELLED, EXPIRED);

    /** Tolerante: un estado desconocido no rompe la maquina, se trata como no terminal. */
    public static PayoutStatus fromWire(String raw) {
        String normalized = StatusNormalizer.normalize(raw);
        if (normalized == null) {
            return UNKNOWN;
        }
        // Un evento payout.returned llega con data.status "returned" y el recurso resuelve en FAILED.
        if ("RETURNED".equals(normalized)) {
            return FAILED;
        }
        if ("CANCELED".equals(normalized)) {
            return CANCELLED;
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
