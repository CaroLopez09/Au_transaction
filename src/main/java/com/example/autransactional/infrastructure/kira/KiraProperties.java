package com.example.autransactional.infrastructure.kira;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kira")
public record KiraProperties(
        @DefaultValue("https://api.balampay.com/sandbox") String baseUrl,
        String apiKey,
        String clientId,
        String password,
        /* Version documentada y recomendada por Kira para integrar. */
        @DefaultValue("2026-04-14") String apiVersion,
        String webhookSecret,
        /* Solo durante la rotacion del secreto de firma: Kira firma con el anterior cerca de un minuto. */
        String webhookSecretPrevious,
        @DefaultValue("3600") long tokenTtlSeconds,
        @DefaultValue("300") long tokenRefreshMarginSeconds,
        @DefaultValue("5000") int connectTimeoutMs,
        @DefaultValue("30000") int readTimeoutMs,
        /*
         * Banco de las cuentas virtuales. Depende del entorno y no es intercambiable:
         * 'portage' en el sandbox devuelve 400 "Invalid bank", y al reves igual.
         */
        @DefaultValue("slovak_savings_bank") String bank,
        /* Habilita lo que solo existe en el sandbox, como simular un deposito. */
        @DefaultValue("true") boolean sandbox) {
}
