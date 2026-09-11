package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.Rail;

import java.util.List;
import java.util.Locale;

/**
 * Riel concreto con el que se cotiza. No es el mismo vocabulario que el account_type del
 * destinatario, y ahi esta la trampa: el riel del pago se deriva EXCLUSIVAMENTE del
 * account_type del destinatario, no del quote.
 *
 * Si no coinciden, la API no falla al cotizar sino al ejecutar el pago, con
 * 422 RECIPIENT_ACCOUNT_TYPE_MISMATCH. Por eso se valida aqui, antes de gastar la
 * cotizacion.
 */
public enum QuotationRail {

    ACH_STANDARD(Rail.ACH),
    ACH_SAME_DAY(Rail.ACH),
    WIRE_DOMESTIC(Rail.WIRE),
    TRON(Rail.WALLET),
    SOLANA(Rail.WALLET),
    POLYGON(Rail.WALLET);

    private final Rail accountType;

    QuotationRail(Rail accountType) {
        this.accountType = accountType;
    }

    public Rail accountType() {
        return accountType;
    }

    /** El valor de 'network' en el destinatario cuando el riel es de wallet. */
    public String network() {
        return accountType == Rail.WALLET ? name().toLowerCase(Locale.ROOT) : null;
    }

    public static List<QuotationRail> validFor(Rail accountType) {
        return List.of(values()).stream().filter(r -> r.accountType == accountType).toList();
    }

    /** Riel por defecto del destinatario. Para wallets hace falta saber la red. */
    public static QuotationRail defaultFor(Rail accountType, String network) {
        return switch (accountType) {
            case ACH -> ACH_STANDARD;
            case WIRE -> WIRE_DOMESTIC;
            case WALLET -> fromNetwork(network);
        };
    }

    public static QuotationRail fromNetwork(String network) {
        if (network == null || network.isBlank()) {
            throw new DomainException("Un destinatario de wallet necesita red (solana, polygon o tron).");
        }
        try {
            QuotationRail rail = valueOf(network.trim().toUpperCase(Locale.ROOT));
            if (rail.accountType != Rail.WALLET) {
                throw new DomainException("Red no soportada: " + network);
            }
            return rail;
        } catch (IllegalArgumentException e) {
            throw new DomainException("Red no soportada: " + network);
        }
    }

    public static QuotationRail from(String raw) {
        if (raw == null) {
            throw new DomainException("Falta el riel de la cotizacion.");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException("Riel no soportado: " + raw);
        }
    }

    /** Se comprueba antes de cotizar: Kira solo lo detecta al ejecutar el pago. */
    public void assertMatches(Rail recipientAccountType) {
        if (this.accountType != recipientAccountType) {
            throw new DomainException("El riel " + name() + " no corresponde a un destinatario "
                    + recipientAccountType + ". Rieles validos: " + validFor(recipientAccountType) + ".");
        }
    }
}
