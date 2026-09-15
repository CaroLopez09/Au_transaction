package com.example.autransactional.infrastructure.bootstrap;

import com.example.autransactional.infrastructure.kira.KiraProperties;
import com.example.autransactional.infrastructure.security.BffSecurityProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequiredSecretsValidatorTest {

    private static final String JWT_OK = "un-secreto-de-al-menos-32-caracteres-de-largo";

    private KiraProperties kira(String apiKey, String clientId, String password, String webhookSecret) {
        return new KiraProperties("https://api.balampay.com", apiKey, clientId, password,
                "2026-04-14", webhookSecret, null, 3600, 300, 5000, 30000, "slovak_savings_bank", true);
    }

    private RequiredSecretsValidator validator(KiraProperties kira, String jwtSecret) {
        return new RequiredSecretsValidator(kira,
                new BffSecurityProperties(jwtSecret, "autransactional-bff", 28800000L));
    }

    @Test
    void arrancaCuandoTodosLosSecretosEstanPresentes() {
        var v = validator(kira("k", "c", "p", "w"), JWT_OK);

        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    void falloAlArrancarNombraTodoLoQueFalta() {
        var v = validator(kira("", null, "  ", null), "");

        var e = assertThrows(IllegalStateException.class, v::afterPropertiesSet);

        assertTrue(e.getMessage().contains("KIRA_API_KEY"));
        assertTrue(e.getMessage().contains("KIRA_CLIENT_ID"));
        assertTrue(e.getMessage().contains("KIRA_PASSWORD"));
        assertTrue(e.getMessage().contains("KIRA_WEBHOOK_SECRET"));
        assertTrue(e.getMessage().contains("BFF_JWT_SECRET"));
    }

    @Test
    void rechazaUnaClaveDeFirmaDemasiadoCorta() {
        var v = validator(kira("k", "c", "p", "w"), "corta");

        var e = assertThrows(IllegalStateException.class, v::afterPropertiesSet);
        assertTrue(e.getMessage().contains("32 caracteres"));
    }
}
