package com.example.autransactional.infrastructure.kira;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sin credenciales, el BFF debe decir "integracion no configurada" (503) y no un error
 * inesperado. Faltar client_id o password tambien cuenta: antes acababa en un NullPointerException.
 */
class KiraCredentialManagerTest {

    private KiraCredentialManager manager(String apiKey, String clientId, String password) {
        KiraProperties properties = new KiraProperties("https://kira.test", apiKey, clientId, password,
                "2026-04-14", null, 3600, 300, 5000, 30000, "slovak_savings_bank", true);
        ObjectMapper mapper = new ObjectMapper();
        return new KiraCredentialManager(RestClient.create(), properties, new KiraErrorParser(mapper),
                Caffeine.newBuilder().build(), mapper);
    }

    @Test
    void sinApiKeyLaIntegracionNoEstaConfigurada() {
        var e = assertThrows(KiraNotConfiguredException.class, () -> manager("", "c", "p").getAccessToken());
        assertTrue(e.getMessage().contains("KIRA_API_KEY"));
    }

    @Test
    void sinClientIdOPasswordTampocoYNoEsUnNullPointer() {
        assertThrows(KiraNotConfiguredException.class, () -> manager("k", null, "p").getAccessToken());
        var e = assertThrows(KiraNotConfiguredException.class, () -> manager("k", "c", " ").getAccessToken());
        assertTrue(e.getMessage().contains("KIRA_PASSWORD"));
    }
}
