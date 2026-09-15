package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.StatusNormalizer;

/**
 * En el pin 2026-04-14 la API colapsa activating/active en "approved", asi que 'approved'
 * NO significa que la cuenta pueda mover fondos. La documentacion indica detectar la cuenta
 * realmente operativa por un account_number real: no nulo y distinto del centinela
 * "PENDING-ACT-ACCOUNT". El evento virtual_account.activated es la unica senal fondos-listos.
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
        if (activatedEventSeen) {
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
