package com.example.autransactional.infrastructure.kira;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

@ConfigurationProperties(prefix = "kira")
public record KiraProperties(
        @DefaultValue("https://api.balampay.com/sandbox") String baseUrl,
        String apiKey,
        String clientId,
        String password,
        /*
         * Una sola version en todas las peticiones (go-live checklist de Kira). El codigo solo
         * entiende las formas de 2026-06-01: RFIs, cotizacion desglosada y estados de cuenta.
         */
        @DefaultValue(KiraProperties.API_VERSION) String apiVersion,
        String webhookSecret,
        /* Solo durante la rotacion del secreto de firma: Kira firma con el anterior cerca de un minuto. */
        String webhookSecretPrevious,
        @DefaultValue("3600") long tokenTtlSeconds,
        @DefaultValue("300") long tokenRefreshMarginSeconds,
        @DefaultValue("5000") int connectTimeoutMs,
        @DefaultValue("30000") int readTimeoutMs,
        /*
         * Banco de las cuentas virtuales; tambien viaja como capabilities.requested_banks al dar de
         * alta la empresa. Kira documenta dos y los dos valen en sandbox y produccion.
         */
        @DefaultValue(KiraProperties.BANK) String bank,
        /* Habilita lo que solo existe en el sandbox, como simular un deposito. */
        @DefaultValue("true") boolean sandbox) {

    public static final String API_VERSION = "2026-06-01";

    /**
     * jp_morgan corresponde al producto usa-virtual-accounts, el que consulta la elegibilidad
     * (EligibleProduct.USA_VIRTUAL_ACCOUNTS). austin_capital_trust es usa-virtual-accounts-act y
     * exige extra_info.memo en WIRE: no esta soportado todavia.
     */
    public static final String BANK = "jp_morgan";

    private static final Set<String> SUPPORTED_BANKS = Set.of(BANK);

    public KiraProperties {
        if (!API_VERSION.equals(apiVersion)) {
            throw new IllegalStateException("kira.api-version debe ser " + API_VERSION
                    + " (recibido: " + apiVersion + "): el BFF solo entiende las formas de esa version.");
        }
        if (!SUPPORTED_BANKS.contains(bank)) {
            throw new IllegalStateException("kira.bank no soportado: " + bank + ". Valores admitidos: "
                    + SUPPORTED_BANKS + ".");
        }
    }
}
