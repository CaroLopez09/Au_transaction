package com.example.autransactional.domain.shared;

import java.util.Locale;

/**
 * Kira devuelve los estados con distinto casing segun la superficie:
 * el 201 de payout responde "created" y el GET responde "CREATED"; los eventos planos
 * usan minusculas y payout.status_changed mayusculas. La documentacion es explicita:
 * comparar SIEMPRE sin distinguir mayusculas y tolerar valores desconocidos.
 */
public final class StatusNormalizer {

    private StatusNormalizer() {
    }

    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean matches(String raw, String expected) {
        return raw != null && expected != null && raw.trim().equalsIgnoreCase(expected.trim());
    }
}
