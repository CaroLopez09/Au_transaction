package com.example.autransactional.interfaces.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La documentacion es parte del entregable: si un endpoint no aparece aqui, el equipo de
 * frontend no puede probarlo. Estas pruebas fallan si alguien saca un endpoint del contrato
 * sin darse cuenta.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String apiDocsCrudo() throws Exception {
        return mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode apiDocs() throws Exception {
        return objectMapper.readTree(apiDocsCrudo());
    }

    @Test
    void laInterfazDeSwaggerSeSirveSinAutenticacion() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void losOchoEndpointsDeVerificacionBiometricaEstanDocumentados() throws Exception {
        JsonNode paths = apiDocs().path("paths");

        assertTrue(paths.has("/api/v1/liveness/config"));
        assertTrue(paths.has("/api/v1/liveness/status"));
        assertTrue(paths.has("/api/v1/liveness/session"));
        assertTrue(paths.has("/api/v1/liveness/session/{sessionId}/result"));
        assertTrue(paths.has("/api/v1/number-challenge/session"));
        assertTrue(paths.has("/api/v1/number-challenge/{challengeId}/verify"));
        assertTrue(paths.has("/api/v1/number-challenge/{challengeId}/status"));
        assertTrue(paths.has("/api/v1/identity/validate"));
    }

    @Test
    void losEndpointsDeNegocioEstanDocumentados() throws Exception {
        JsonNode paths = apiDocs().path("paths");

        assertTrue(paths.has("/api/auth/login"));
        assertTrue(paths.has("/api/onboarding"));
        assertTrue(paths.has("/api/onboarding/refresh"));
        assertTrue(paths.has("/api/ubos"));
        assertTrue(paths.has("/api/ubos/sync"));
        assertTrue(paths.has("/api/ubos/liveness-links"));
        assertTrue(paths.has("/api/quotations"));
        assertTrue(paths.has("/api/recipients"));
        assertTrue(paths.has("/api/recipients/{id}/archive"));
        assertTrue(paths.has("/api/virtual-accounts"));
        assertTrue(paths.has("/api/virtual-accounts/{id}/balance"));
        assertTrue(paths.has("/api/deposits"));
        assertTrue(paths.has("/api/virtual-accounts/{id}/deposits"));
        assertTrue(paths.has("/api/payouts"));
        assertTrue(paths.has("/api/payouts/{id}/approve"));
        assertTrue(paths.has("/api/rfis"));
        assertTrue(paths.has("/api/rfis/sync"));
        assertTrue(paths.has("/api/rfis/{id}/items"));
        assertTrue(paths.has("/api/webhooks/kira"));
    }

    @Test
    void elEsquemaDeSeguridadEsElJwtPropioDelBff() throws Exception {
        JsonNode esquemas = apiDocs().path("components").path("securitySchemes");

        assertTrue(esquemas.has("bearer-jwt"));
        assertEquals("bearer", esquemas.path("bearer-jwt").path("scheme").asText());
    }

    @Test
    void laDocumentacionNoFiltraSecretosNiLaUrlDeKira() throws Exception {
        String crudo = apiDocsCrudo().toLowerCase();

        assertFalse(crudo.contains("x-api-key"));
        assertFalse(crudo.contains("balampay"));
        assertFalse(crudo.contains("webhook-secret"));
    }
}
