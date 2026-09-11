package com.example.autransactional.domain.shared;

import java.util.Locale;

/**
 * Riel de movimiento de fondos. Discrimina account.account_type en POST /v1/recipients
 * y clasifica el origen en los depositos entrantes.
 *
 * Kira no expone actualizacion ni borrado de destinatarios: para corregir uno se crea
 * un reemplazo y el anterior se archiva localmente.
 */
public enum Rail {
    ACH,
    WIRE,
    WALLET;

    public static Rail from(String raw) {
        if (raw == null) {
            throw new DomainException("account_type es obligatorio");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException("account_type no soportado: " + raw);
        }
    }

    /** Tolerante: un riel desconocido en un evento no debe romper la proyeccion. */
    public static Rail fromWireOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
