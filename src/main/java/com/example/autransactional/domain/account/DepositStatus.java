package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.StatusNormalizer;

import java.util.Set;

/**
 * Estado del deposito entrante.
 *
 * REFUNDED existe porque un deposito completado puede revertirse despues: la ficha tiene
 * que soportar el paso COMPLETED -> REFUNDED, que es justo lo que reproduce el valor
 * magico de 11 en el simulador del sandbox.
 */
public enum DepositStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED;

    /** Una vez revertido o fallido, el deposito ya no vuelve a acreditar. */
    private static final Set<DepositStatus> TERMINAL = Set.of(FAILED, REFUNDED);

    public static DepositStatus fromWire(String raw) {
        String normalized = StatusNormalizer.normalize(raw);
        if (normalized == null) {
            return COMPLETED;
        }
        for (DepositStatus s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        return switch (normalized) {
            case "RETURNED", "REVERSED" -> REFUNDED;
            // KYT_REJECTED: el control de transacciones de Kira no dejo pasar los fondos.
            case "DECLINED", "REJECTED", "KYT_REJECTED" -> FAILED;
            case "PROCESSING", "IN_TRANSIT", "IN_REVIEW", "KYT_PENDING" -> PENDING;
            default -> COMPLETED;
        };
    }

    /**
     * Estado que implica cada evento de la familia de depositos.
     *
     * El nombre del evento es mas fiable que el 'status' del payload, porque hay eventos
     * cuyo estado llega vacio y el propio nombre ya dice lo que paso.
     */
    public static DepositStatus fromEventName(String eventName, String rawStatus) {
        if (eventName != null) {
            switch (eventName) {
                case "virtual_account.deposit_funds_in_transit":
                    return PENDING;
                case "virtual_account.deposit_funds_failed":
                    return FAILED;
                case "virtual_account.deposit_returned":
                    return REFUNDED;
                case "virtual_account.deposit_funds_received",
                     "virtual_account.microdeposit_funds_received",
                     "virtual_account.deposit_funds_in_destination":
                    return rawStatus == null ? COMPLETED : fromWire(rawStatus);
                default:
                    break;
            }
        }
        return fromWire(rawStatus);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Solo un deposito completado suma saldo disponible. */
    public boolean creditsBalance() {
        return this == COMPLETED;
    }
}
