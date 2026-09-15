package com.example.autransactional.infrastructure.observability;

import com.example.autransactional.domain.compliance.AuditLog;
import com.example.autransactional.domain.compliance.AuditLogRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Arquitectura §5: correlacion de peticiones y metricas de la integracion con Kira. */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void cadaRespuestaLlevaSuIdDePeticionYRespetaUnoValido() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
        mockMvc.perform(get("/actuator/health").header(RequestIdFilter.HEADER, "soporte-1234"))
                .andExpect(header().string(RequestIdFilter.HEADER, "soporte-1234"));
        // Texto con forma de inyeccion en los logs: se sustituye.
        mockMvc.perform(get("/actuator/health").header(RequestIdFilter.HEADER, "x\nFAKE LOG"))
                .andExpect(header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.not("x\nFAKE LOG")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void lasMetricasNoSonParaUnaEmpresa() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PLATFORM_OPERATOR")
    void laPlataformaPasaLaReglaDeSeguridadDeLasMetricas() throws Exception {
        // Las pruebas no activan el endpoint de metricas (404); lo que se comprueba es que no hay 403.
        int code = mockMvc.perform(get("/actuator/metrics")).andReturn().getResponse().getStatus();
        assertNotEquals(403, code);
        assertNotEquals(401, code);
    }

    @Test
    void lasRutasDeKiraSeEtiquetanSinIds() {
        assertEquals("/v1/virtual-accounts/{id}/payout", IntegrationMetrics.route("/v1/virtual-accounts/0b6f6d0e-1c2a-4f7e-9a51-3f1a2b3c4d5e/payout"));
        assertEquals("/v1/rfis/{id}/items/{id}/ubo-link", IntegrationMetrics.route("/v1/rfis/rfi_1/items/i-ubo/ubo-link"));
        assertEquals("/v1/rfis", IntegrationMetrics.route("/v1/rfis?status=pending"));
    }

    @Test
    void registraLatenciaYResultadoDeKiraYLosWebhooks() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationMetrics metrics = new IntegrationMetrics(registry);

        metrics.recordKiraCall("GET", "/v1/users/usr_1", "503", TimeUnit.MILLISECONDS.toNanos(120));
        metrics.webhookReceived("invalid_signature");

        assertEquals(1, registry.get("kira.api.requests").tag("route", "/v1/users/{id}").tag("outcome", "503").timer().count());
        assertEquals(1.0, registry.get("kira.webhooks.received").tag("result", "invalid_signature").counter().count());
    }

    @Test
    void laAuditoriaGuardaElIdDeLaPeticion() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AuditTrail trail = new AuditTrail(repository, new ObjectMapper());
        MDC.put(RequestIdFilter.MDC_KEY, "req-12345678");
        try {
            trail.record(null, "prueba", "tenant", "t-1", null, "OK", null);
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).append(entry.capture());
        assertTrue(entry.getValue().changes().contains("\"requestId\":\"req-12345678\""));
    }
}
