package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.application.webhook.ProcessWebhookUseCase;
import com.example.autransactional.infrastructure.kira.KiraNotConfiguredException;
import com.example.autransactional.infrastructure.persistence.WebhookEventEntity;
import com.example.autransactional.infrastructure.persistence.WebhookEventJpaRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * El ingress responde 2xx en cuanto guarda el evento: si la proyeccion falla despues, Kira no
 * reintenta y la fila con processed = false es el unico rastro del cambio de estado. Dos de esos
 * eventos (el motivo del rechazo del KYB y el resultado de la prueba de vida) no estan en ningun
 * GET, asi que perderlos es perderlos para siempre.
 */
class WebhookReprojectionWorkerTest {

    private final WebhookEventJpaRepository events = mock(WebhookEventJpaRepository.class);
    private final ProcessWebhookUseCase webhooks = mock(ProcessWebhookUseCase.class);
    private final WebhookReprojectionWorker worker = new WebhookReprojectionWorker(events, webhooks, 50);

    private WebhookEventEntity pendiente(String eventId) {
        WebhookEventEntity evento = new WebhookEventEntity();
        evento.setId("row-" + eventId);
        evento.setEventId(eventId);
        evento.setEventType("user.verification.failed");
        evento.setPayload("{\"event\":\"user.verification.failed\"}");
        evento.setCreatedAt(Instant.now());
        evento.setProcessingError("la base estaba caida");
        return evento;
    }

    private void devuelve(WebhookEventEntity... pendientes) {
        when(events.findByProcessedFalseAndRetryCountLessThanOrderByCreatedAtAsc(anyInt()))
                .thenReturn(List.of(pendientes));
    }

    @Test
    void unEventoPendienteSeVuelveAProyectar() throws Exception {
        WebhookEventEntity evento = pendiente("evt_1");
        devuelve(evento);

        worker.reprojectPendingEvents();

        verify(webhooks).reproject(evento);
    }

    @Test
    void unEventoQueSigueFallandoConservaElMotivoYNoSeMarcaComoProcesado() throws Exception {
        WebhookEventEntity evento = pendiente("evt_1");
        devuelve(evento);
        doThrow(new IllegalStateException("la empresa sigue sin existir")).when(webhooks).reproject(evento);

        worker.reprojectPendingEvents();

        ArgumentCaptor<WebhookEventEntity> guardado = ArgumentCaptor.forClass(WebhookEventEntity.class);
        verify(events).save(guardado.capture());
        assertEquals("la empresa sigue sin existir", guardado.getValue().getProcessingError());
        assertFalse(guardado.getValue().isProcessed(), "un evento que fallo no puede quedar como procesado");
    }

    @Test
    void cadaFalloCuentaUnIntentoMas() throws Exception {
        WebhookEventEntity evento = pendiente("evt_1");
        evento.setRetryCount(2);
        devuelve(evento);
        doThrow(new IllegalStateException("timeout")).when(webhooks).reproject(evento);

        worker.reprojectPendingEvents();

        assertEquals(3, evento.getRetryCount());
    }

    @Test
    void alQuintoIntentoElEventoQuedaComoFallidoDefinitivo() throws Exception {
        WebhookEventEntity evento = pendiente("evt_veneno");
        evento.setRetryCount(WebhookReprojectionWorker.MAX_RETRIES - 1);
        devuelve(evento);
        doThrow(new IllegalStateException("payload corrupto")).when(webhooks).reproject(evento);

        worker.reprojectPendingEvents();

        assertEquals(WebhookReprojectionWorker.MAX_RETRIES, evento.getRetryCount());
        assertEquals("Max retries reached", evento.getProcessingError());
    }

    @Test
    void losEventosAgotadosNoSeVuelvenAPedir() {
        devuelve();

        worker.reprojectPendingEvents();

        // El tope viaja en la consulta: una fila envenenada deja de ocupar sitio en el lote.
        verify(events).findByProcessedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
                WebhookReprojectionWorker.MAX_RETRIES);
    }

    @Test
    void unFalloEnUnEventoNoDetieneALosDemas() throws Exception {
        WebhookEventEntity roto = pendiente("evt_roto");
        WebhookEventEntity bueno = pendiente("evt_bueno");
        devuelve(roto, bueno);
        doThrow(new IllegalStateException("timeout")).when(webhooks).reproject(roto);

        worker.reprojectPendingEvents();

        verify(webhooks).reproject(bueno);
    }

    @Test
    void sinCredencialesDeKiraSeCortaElLoteYLaFilaSiguePendiente() throws Exception {
        WebhookEventEntity primero = pendiente("evt_1");
        devuelve(primero, pendiente("evt_2"));
        doThrow(new KiraNotConfiguredException("Falta KIRA_API_KEY.")).when(webhooks).reproject(any());

        worker.reprojectPendingEvents();

        verify(webhooks, times(1)).reproject(any());
        // No es culpa del evento: ni se le apunta un error ni se le gasta un intento.
        verify(events, never()).save(any());
        assertEquals(0, primero.getRetryCount());
    }

    @Test
    void sinEventosPendientesNoSeLlamaAlCasoDeUso() {
        devuelve();

        worker.reprojectPendingEvents();

        verifyNoInteractions(webhooks);
    }

    @Test
    void elLoteRespetaElTamanoConfigurado() throws Exception {
        WebhookReprojectionWorker acotado = new WebhookReprojectionWorker(events, webhooks, 2);
        when(events.findByProcessedFalseAndRetryCountLessThanOrderByCreatedAtAsc(anyInt())).thenReturn(
                IntStream.range(0, 5).mapToObj(i -> pendiente("evt_" + i)).toList());

        acotado.reprojectPendingEvents();

        verify(webhooks, times(2)).reproject(any());
    }
}
