package com.example.autransactional.domain.compliance;

import com.example.autransactional.domain.shared.StatusNormalizer;

/**
 * Ciclo de vida de una solicitud de informacion (RFI) planteada por KiraFin.
 *
 * Son los cuatro estados de Kira y ninguno mas. Un item devuelto no crea un estado propio:
 * el RFI vuelve a PENDING, que significa siempre "te toca responder".
 */
public enum RfiStatus {
    PENDING,
    ANSWERED,
    RESOLVED,
    NOT_RESOLVED;

    /**
     * Un valor desconocido cae a PENDING: mostrar de mas un RFI en la bandeja es un susto,
     * esconder uno abierto deja bloqueado un pago hasta que vence.
     */
    public static RfiStatus fromWire(String raw) {
        String normalized = StatusNormalizer.normalize(raw);
        if (normalized == null) {
            return PENDING;
        }
        for (RfiStatus s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        return switch (normalized) {
            case "NOT-RESOLVED", "NOTRESOLVED", "EXPIRED" -> NOT_RESOLVED;
            default -> PENDING;
        };
    }

    public boolean isTerminal() {
        return this == RESOLVED || this == NOT_RESOLVED;
    }

    /** Mientras no este cerrado admite respuestas: tambien en ANSWERED, si Kira devuelve un item. */
    public boolean isOpen() {
        return !isTerminal();
    }
}
