package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;

import java.util.Locale;

/** Tipo de cuenta bancaria del destinatario. */
public enum BankAccountKind {
    CHECKING,
    SAVINGS;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BankAccountKind from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException("El tipo de cuenta debe ser 'checking' o 'savings'.");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException("Tipo de cuenta no soportado: " + raw);
        }
    }
}
