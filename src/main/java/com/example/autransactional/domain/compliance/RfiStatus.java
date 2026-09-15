package com.example.autransactional.domain.compliance;

import com.example.autransactional.domain.shared.StatusNormalizer;

/**
 * Ciclo de vida de una solicitud de informacion (RFI) planteada por KiraFin.
 *
 * Los cuatro estados que Kira devuelve mas WITHDRAWN: Kira lo cuenta como cierre, pero un RFI
 * retirado responde 404 en todas sus rutas y nunca aparece como estado, asi que lo asienta el BFF
 * al recibir ese 404. Un item devuelto no crea un estado propio: el RFI vuelve a PENDING.
 */
public enum RfiStatus {
    PENDING,
    ANSWERED,
    RESOLVED,
    NOT_RESOLVED,
    WITHDRAWN;

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
        return this == RESOLVED || this == NOT_RESOLVED || this == WITHDRAWN;
    }

    /** Mientras no este cerrado admite respuestas: tambien en ANSWERED, si Kira devuelve un item. */
    public boolean isOpen() {
        return !isTerminal();
    }
}
