package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;

import java.util.List;
import java.util.Locale;

/**
 * Stablecoin del destinatario y las redes en las que Kira la admite.
 *
 * Los pares no son intercambiables: USDC NO existe en tron. Un par invalido se rechaza
 * aqui porque la alternativa es descubrirlo con un pago retenido.
 */
public enum WalletToken {

    USDC("USDC", List.of("polygon", "solana")),
    USDT("USDT", List.of("polygon", "solana", "tron")),
    COPM("COPm", List.of("polygon"));

    private final String wireValue;
    private final List<String> networks;

    WalletToken(String wireValue, List<String> networks) {
        this.wireValue = wireValue;
        this.networks = networks;
    }

    public String wireValue() {
        return wireValue;
    }

    public List<String> networks() {
        return networks;
    }

    public static WalletToken from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException("El destinatario de wallet necesita token (USDC, USDT o COPm).");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (WalletToken token : values()) {
            if (token.name().equals(normalized)) {
                return token;
            }
        }
        throw new DomainException("Token no soportado: " + raw);
    }

    public void assertSupportedOn(String network) {
        String normalized = network == null ? "" : network.trim().toLowerCase(Locale.ROOT);
        if (!networks.contains(normalized)) {
            throw new DomainException(wireValue + " no esta soportado en la red '" + network
                    + "'. Redes validas: " + networks + ".");
        }
    }
}
