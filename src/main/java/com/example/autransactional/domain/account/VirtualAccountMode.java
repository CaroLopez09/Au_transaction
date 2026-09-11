package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.DomainException;

import java.util.Locale;

/**
 * Modo de la cuenta virtual. Es INMUTABLE una vez creada: cambiar de fiat a crypto
 * significa abrir otra cuenta, no editar esta.
 */
public enum VirtualAccountMode {
    FIAT,
    CRYPTO;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static VirtualAccountMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FIAT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException("Modo de cuenta no soportado: " + raw);
        }
    }
}
