package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.shared.PostalAddress;
import com.example.autransactional.domain.treasury.Recipient;
import com.example.autransactional.domain.treasury.RecipientAccount;

import java.time.Instant;

/**
 * Destinatario para el portal.
 *
 * La cuenta va enmascarada: el directorio no necesita mostrar el numero completo, y cada
 * pantalla que lo muestre es una copia mas de un dato bancario.
 */
public record RecipientView(
        String id,
        String kiraRecipientId,
        String name,
        String rail,
        String network,
        String bankName,
        String maskedDestination,
        String status,
        boolean registeredInKira,
        boolean alreadyExisted,
        String replacedByRecipientId,
        PostalAddress bankAddress,
        Instant createdAt) {

    public static RecipientView from(Recipient r) {
        return from(r, false);
    }

    public static RecipientView from(Recipient r, boolean alreadyExisted) {
        return new RecipientView(
                r.getId(),
                r.getKiraRecipientId(),
                r.getName(),
                r.getRail().name(),
                r.getNetwork(),
                r.getAccount() instanceof RecipientAccount.Ach ach ? ach.bankName()
                        : r.getAccount() instanceof RecipientAccount.Wire wire ? wire.bankName() : null,
                mask(r.getAccount().destination()),
                r.getStatus().name(),
                r.isRegisteredInKira(),
                alreadyExisted,
                r.getReplacedByRecipientId(),
                // state y postal_code salen del espejo local: Kira los devuelve vacios.
                r.getAccount() instanceof RecipientAccount.Wire wire ? wire.bankAddress() : null,
                r.getCreatedAt());
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return value;
        }
        return "****" + value.substring(value.length() - 4);
    }
}
