package com.example.autransactional.interfaces.rest.identity;

import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.infrastructure.persistence.TenantEntity;
import com.example.autransactional.infrastructure.persistence.TenantJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recorre el flujo completo tal como lo hace la libreria de onboarding:
 * reto de voz, prueba de vida, documentos y veredicto.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdentityFlowTest {

    private static final String CLIENT_ID = "juriscop";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantJpaRepository tenants;

    @BeforeEach
    void organizacionActiva() {
        if (tenants.findById(CLIENT_ID).isEmpty()) {
            TenantEntity t = new TenantEntity();
            t.setId(CLIENT_ID);
            t.setName("Juriscop");
            t.setTaxId("900123456-1");
            t.setStatus(TenantStatus.VERIFIED);
            tenants.save(t);
        }
    }

    private JsonNode json(String body) {
        return objectMapper.readTree(body);
    }

    private byte[] imagen() {
        // Cualquier contenido de tamano suficiente sirve: el adaptador de desarrollo
        // solo mira que la imagen tenga bytes utiles.
        return new byte[512];
    }

    private JsonNode crearReto(String verificationId) throws Exception {
        var request = post("/api/v1/number-challenge/session").param("clientId", CLIENT_ID);
        if (verificationId != null) {
            request = request.param("verificationId", verificationId);
        }
        return json(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void superarReto(String challengeId, String numero) throws Exception {
        var grabacion = new MockMultipartFile("recording", "reto.webm", "video/webm",
                ("audio simulado: " + numero).getBytes(StandardCharsets.UTF_8));

        String body = mockMvc.perform(multipart("/api/v1/number-challenge/" + challengeId + "/verify")
                        .file(grabacion))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(json(body).path("passed").asBoolean(), body);
    }

    private String crearLiveness(String verificationId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/liveness/session")
                        .param("clientId", CLIENT_ID)
                        .param("verificationId", verificationId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json(body).path("sessionId").asText();
    }

    private void superarLiveness(String livenessSessionId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/liveness/session/" + livenessSessionId + "/result"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(json(body).path("passed").asBoolean(), body);
    }

    private JsonNode validar(String verificationId, String challengeId, String livenessSessionId)
            throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/identity/validate")
                        .file(new MockMultipartFile("selfieImage", "s.jpg", "image/jpeg", imagen()))
                        .file(new MockMultipartFile("documentFrontImage", "f.jpg", "image/jpeg", imagen()))
                        .file(new MockMultipartFile("documentBackImage", "b.jpg", "image/jpeg", imagen()))
                        .param("verificationId", verificationId)
                        .param("challengeId", challengeId)
                        .param("livenessSessionId", livenessSessionId)
                        .param("countryCode", "COL")
                        .param("documentType", "CC"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json(body);
    }

    // ── El recorrido completo ────────────────────────────────────────────

    @Test
    void elFlujoCompletoTerminaEnUnVeredictoDelServidor() throws Exception {
        JsonNode reto = crearReto(null);
        String verificationId = reto.path("verificationId").asText();
        String challengeId = reto.path("challengeId").asText();

        assertTrue(reto.path("challengeNumber").asText().matches("\\d{4}"));

        superarReto(challengeId, reto.path("challengeNumber").asText());

        String livenessSessionId = crearLiveness(verificationId);
        superarLiveness(livenessSessionId);

        JsonNode resultado = validar(verificationId, challengeId, livenessSessionId);

        assertEquals(verificationId, resultado.path("verificationId").asText());
        assertEquals("APPROVED", resultado.path("status").asText());
        assertTrue(resultado.path("matchScore").asDouble() > 0);
        assertEquals("MARIA", resultado.path("documentData").path("firstName").asText());
    }

    @Test
    void laPruebaDeVidaNoSeOfreceAntesDeSuperarElRetoDeVoz() throws Exception {
        JsonNode reto = crearReto(null);

        String body = mockMvc.perform(post("/api/v1/liveness/session")
                        .param("clientId", CLIENT_ID)
                        .param("verificationId", reto.path("verificationId").asText()))
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();

        assertEquals("VERIFICATION_INCOMPLETE", json(body).path("code").asText());
    }

    @Test
    void unLivenessDeOtraSesionNoValidaLosDocumentos() throws Exception {
        // Sesion A: completa.
        JsonNode retoA = crearReto(null);
        String vA = retoA.path("verificationId").asText();
        superarReto(retoA.path("challengeId").asText(), retoA.path("challengeNumber").asText());
        String livenessA = crearLiveness(vA);
        superarLiveness(livenessA);

        // Sesion B: tambien completa, con su propio liveness.
        JsonNode retoB = crearReto(null);
        String vB = retoB.path("verificationId").asText();
        superarReto(retoB.path("challengeId").asText(), retoB.path("challengeNumber").asText());
        superarLiveness(crearLiveness(vB));

        // Intento de pegar el liveness de A en la sesion B.
        String body = mockMvc.perform(multipart("/api/v1/identity/validate")
                        .file(new MockMultipartFile("documentFrontImage", "f.jpg", "image/jpeg", imagen()))
                        .param("verificationId", vB)
                        .param("challengeId", retoB.path("challengeId").asText())
                        .param("livenessSessionId", livenessA)
                        .param("countryCode", "COL")
                        .param("documentType", "CC"))
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();

        assertEquals("SESSION_MISMATCH", json(body).path("code").asText());
    }

    @Test
    void sinMovimientoLabialElRetoFallaConSuCodigo() throws Exception {
        JsonNode reto = crearReto(null);
        var grabacion = new MockMultipartFile("recording", "reto.webm", "video/webm",
                ("NOLIPS " + reto.path("challengeNumber").asText()).getBytes(StandardCharsets.UTF_8));

        String body = mockMvc.perform(
                        multipart("/api/v1/number-challenge/" + reto.path("challengeId").asText() + "/verify")
                                .file(grabacion))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(json(body).path("passed").asBoolean());
        assertEquals("LIP_MOVEMENT_NOT_DETECTED", json(body).path("failureReason").asText());
    }

    @Test
    void unClientIdDesconocidoNoAbreSesion() throws Exception {
        String body = mockMvc.perform(post("/api/v1/number-challenge/session")
                        .param("clientId", "organizacion-inexistente"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertEquals("UNAUTHORIZED", json(body).path("code").asText());
    }

    @Test
    void laConfiguracionDeLivenessNoExponeCredenciales() throws Exception {
        String body = mockMvc.perform(get("/api/v1/liveness/config"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(json(body).path("enabled").asBoolean());
        assertFalse(body.toLowerCase().contains("secret"));
        assertFalse(body.toLowerCase().contains("password"));
    }
}
