package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.StatusNormalizer;

import java.util.Set;

/**
 * Estado del deposito entrante.
 *
 * Los seis valores de docs.kirafin.ai/reference/virtual-accounts/values. Solo FAILED y
 * REFUNDED son finales: un COMPLETED todavia puede retenerse o devolverse, que es justo lo que
 * reproduce el valor magico de 11 en el simulador del sandbox.
 */
public enum DepositStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED,
    /** Retenido mientras corre un control de cumplimiento. Bloquea todos los pagos de la cuenta. */
    KYT_PENDING,
    /**
     * Congelado tras un control rechazado. No es FAILED (fallo tecnico) ni es final: una decision
     * de cumplimiento lo pasa a REFUNDED o, si se retira el rechazo, a COMPLETED.
     */
    KYT_REJECTED;

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
            case "DECLINED", "REJECTED" -> FAILED;
            case "COMPLETE", "SETTLED", "CREDITED" -> COMPLETED;
            // Un valor que no se conoce nunca acredita saldo: mostrar dinero que no esta es el
            // unico error que no se puede deshacer con una disculpa.
            default -> PENDING;
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
            // Nombres de docs.kirafin.ai/webhooks/event-catalog. Ninguno de estos payloads trae
            // 'status' (ver notification-examples), asi que el nombre es lo unico que lo dice.
            switch (eventName.toLowerCase(java.util.Locale.ROOT)) {
                // Si el payload trae estado (p. ej. KYT_PENDING en una revision), ese manda.
                case "virtual_account.deposit_scheduled",
                     "virtual_account.deposit_funds_in_transit",
                     "virtual_account.deposit_in_review":
                    return rawStatus == null ? PENDING : fromWire(rawStatus);
                case "virtual_account.deposit_funds_failed":
                    return FAILED;
                // deposit_returned no esta en el catalogo; se conserva por si llega de una version vieja.
                case "virtual_account.deposit_funds_refunded",
                     "virtual_account.deposit_returned":
                    return REFUNDED;
                case "virtual_account.deposit_funds_received",
                     "virtual_account.microdeposit_funds_received",
                     "virtual_account.deposit_funds_in_destination":
                    return rawStatus == null ? COMPLETED : fromWire(rawStatus);
                default:
                    break;
            }
        }
        // Evento de deposito que aun no se conoce y sin estado: no se asume que acredita.
        return rawStatus == null ? PENDING : fromWire(rawStatus);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Retenido por cumplimiento: ni acreditado ni fallido, y detiene los pagos de la cuenta. */
    public boolean isHeld() {
        return this == KYT_PENDING || this == KYT_REJECTED;
    }

    /** Solo un deposito completado suma saldo disponible. */
    public boolean creditsBalance() {
        return this == COMPLETED;
    }
}
