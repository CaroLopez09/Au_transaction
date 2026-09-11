package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;

import java.util.Locale;

/** Naturaleza del pago que Kira reporta al banco corresponsal. */
public enum NatureOfPayment {
    VENDOR,
    POBO,
    FIRST_PARTY,
    SPOT_3P,
    SPOT_1P,
    RELATED_ENTITIES,
    OTHER;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static NatureOfPayment from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException("Naturaleza de pago no soportada: " + raw);
        }
    }

    /** Un pago a uno mismo no necesita justificar el destino con documentos. */
    public boolean requiresSupportingDocuments() {
        return this != FIRST_PARTY;
    }
}
