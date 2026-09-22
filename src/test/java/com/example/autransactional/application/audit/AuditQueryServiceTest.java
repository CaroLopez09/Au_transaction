package com.example.autransactional.application.audit;

import com.example.autransactional.application.webhook.ProcessWebhookUseCase;
import com.example.autransactional.domain.compliance.AuditLogRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.infrastructure.persistence.WebhookEventEntity;
import com.example.autransactional.infrastructure.persistence.WebhookEventJpaRepository;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** G-19-bis / panel de incidencias: eventos de Kira que fallaron al proyectarse. */
class AuditQueryServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final AuditLogRepository auditLogs = mock(AuditLogRepository.class);
    private final OperatorUserRepository users = mock(OperatorUserRepository.class);
    private final WebhookEventJpaRepository events = mock(WebhookEventJpaRepository.class);
    private final ProcessWebhookUseCase webhooks = mock(ProcessWebhookUseCase.class);

    private final AuthenticatedOperator admin =
            new AuthenticatedOperator("admin-1", "admin@juriscop.test", TENANT, Role.ADMIN);

    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new AuditQueryService(auditLogs, users, events, webhooks);
    }

    private WebhookEventEntity poisoned() {
        WebhookEventEntity e = new WebhookEventEntity();
        e.setId("evt-row-1");
        e.setEventId("evt-1");
        e.setEventType("payout.status_changed");
        e.setPayload("{}");
        e.setTenantId(TENANT.value());
        e.setProcessed(false);
        e.setProcessingError("Max retries reached");
        e.setRetryCount(5);
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Test
    void soloTraeEventosConAlMenosUnFalloDeProyeccion() {
        when(events.findByTenantIdAndProcessingErrorIsNotNullOrderByCreatedAtDesc(eq(TENANT.value()), any(Pageable.class)))
                .thenReturn(List.of(poisoned()));

        var incidencias = service.incidents(admin, 50);

        assertEquals(1, incidencias.size());
        assertTrue(incidencias.get(0).exhausted());
        assertEquals("Max retries reached", incidencias.get(0).processingError());
    }

    @Test
    void reintentarLimpiaElErrorSiLaProyeccionAhoraFunciona() throws Exception {
        WebhookEventEntity evento = poisoned();
        when(events.findByEventId("evt-1")).thenReturn(Optional.of(evento));
        doNothing().when(webhooks).reproject(evento);

        var vista = service.retry(admin, "evt-1");

        assertNull(vista.processingError());
        assertEquals(0, vista.retryCount());
        assertFalse(vista.exhausted());
        verify(events).save(evento);
    }

    @Test
    void siElReintentoManualVuelveAFallarQuedaRegistrado() throws Exception {
        WebhookEventEntity evento = poisoned();
        when(events.findByEventId("evt-1")).thenReturn(Optional.of(evento));
        doThrow(new RuntimeException("Sigue sin poder proyectarse")).when(webhooks).reproject(evento);

        var vista = service.retry(admin, "evt-1");

        assertEquals("Sigue sin poder proyectarse", vista.processingError());
        assertEquals(1, vista.retryCount());
    }

    @Test
    void unEventoDeOtraEmpresaNoSeReintenta() {
        WebhookEventEntity ajeno = poisoned();
        ajeno.setTenantId("otra-empresa");
        when(events.findByEventId("evt-1")).thenReturn(Optional.of(ajeno));

        assertThrows(DomainException.class, () -> service.retry(admin, "evt-1"));
    }

    @Test
    void unEventoInexistenteAlReintentarSeRechaza() {
        when(events.findByEventId(anyString())).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> service.retry(admin, "no-existe"));
    }
}
