package com.example.autransactional.interfaces.webhook;

import com.example.autransactional.infrastructure.kira.KiraWebhookVerifier;
import com.example.autransactional.infrastructure.persistence.WebhookEventJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "kira.webhook-secret=secreto-de-firma-de-pruebas")
class KiraWebhookControllerTest {

    private static final String SECRET = "secreto-de-firma-de-pruebas";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookEventJpaRepository events;

    private String sign(String body) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String evento(String eventId) {
        return "{\"event\":\"payout.completed\",\"data\":{\"event_id\":\"" + eventId
                + "\",\"status\":\"completed\",\"payout_id\":\"kira-1\"}}";
    }

    @Test
    void elWebhookNoExigeJwtPeroSiFirmaValida() throws Exception {
        String body = evento("evt-1");

        mockMvc.perform(post("/api/webhooks/kira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(KiraWebhookVerifier.SIGNATURE_HEADER, sign(body))
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void rechazaUnaFirmaInvalidaSinTocarLaBase() throws Exception {
        mockMvc.perform(post("/api/webhooks/kira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(KiraWebhookVerifier.SIGNATURE_HEADER, "0".repeat(64))
                        .content(evento("evt-2")))
                .andExpect(status().isUnauthorized());

        assertFalse(events.existsByEventId("evt-2"));
    }

    @Test
    void rechazaSiFaltaLaCabeceraDeFirma() throws Exception {
        mockMvc.perform(post("/api/webhooks/kira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evento("evt-3")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void elMismoEventIdSoloSeAlmacenaUnaVez() throws Exception {
        String body = evento("evt-4");
        String firma = sign(body);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/webhooks/kira")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(KiraWebhookVerifier.SIGNATURE_HEADER, firma)
                            .content(body))
                    .andExpect(status().isOk());
        }

        // El procesamiento es asincrono: esperamos a que la proyeccion se asiente.
        for (int i = 0; i < 50 && !events.existsByEventId("evt-4"); i++) {
            Thread.sleep(100);
        }
        Thread.sleep(300);

        long almacenados = events.findAll().stream()
                .filter(e -> "evt-4".equals(e.getEventId()))
                .count();
        assertEquals(1, almacenados, "tres entregas del mismo data.event_id deben dejar una sola fila");
    }
}
