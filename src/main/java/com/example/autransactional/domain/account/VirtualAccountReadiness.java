package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.StatusNormalizer;

/**
 * Cuando una cuenta puede mover fondos. En 2026-06-01 lo dice el estado 'active' (y el evento
 * virtual_account.activated, que trae ese mismo estado). Se conserva la deteccion por un
 * account_number real, no nulo y distinto del centinela "PENDING-ACT-ACCOUNT", para las filas
 * que se proyectaron con la version anterior.
 */
public final class VirtualAccountReadiness {

    public static final String ACT_PENDING_SENTINEL = "PENDING-ACT-ACCOUNT";

    private VirtualAccountReadiness() {
    }

    public static boolean isFundsReady(String status, String accountNumber, boolean activatedEventSeen) {
        // Una cuenta congelada ya estuvo activa: el evento visto no la habilita mientras dure.
        if (StatusNormalizer.matches(status, "frozen")) {
            return false;
        }
        if (activatedEventSeen || StatusNormalizer.matches(status, "active")) {
            return true;
        }
        if (StatusNormalizer.matches(status, "declined") || StatusNormalizer.matches(status, "deactivated")
                || StatusNormalizer.matches(status, "failed")) {
            return false;
        }
        return accountNumber != null
                && !accountNumber.isBlank()
                && !ACT_PENDING_SENTINEL.equalsIgnoreCase(accountNumber.trim());
    }
}
