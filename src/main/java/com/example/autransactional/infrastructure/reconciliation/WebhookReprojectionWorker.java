package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.application.webhook.ProcessWebhookUseCase;
import com.example.autransactional.infrastructure.kira.KiraNotConfiguredException;
import com.example.autransactional.infrastructure.persistence.WebhookEventEntity;
import com.example.autransactional.infrastructure.persistence.WebhookEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Eventos almacenados y nunca proyectados: las filas de webhooks_log con processed = false.
 *
 * El ingress responde 2xx en cuanto guarda el evento, asi que un fallo posterior de la proyeccion
 * no se le puede devolver a Kira, que ademas entrega una sola vez y sin reintentos. La fila con
 * processing_error es lo unico que queda de ese cambio de estado, y aqui se vuelve a intentar.
 *
 * Es el complemento de los otros cuatro workers: aquellos preguntan por el recurso, este recupera
 * eventos cuyo dato NO esta en ningun GET (el motivo del rechazo del KYB y el resultado real de la
 * prueba de vida sólo viajan en el webhook).
 */
@Component
@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebhookReprojectionWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookReprojectionWorker.class);

    /**
     * Intentos antes de dar un evento por perdido. Sin este tope, un evento que nunca va a
     * proyectarse se reintenta indefinidamente y, al ir el lote de mas antiguo a mas nuevo,
     * acaba desplazando a los eventos recientes.
     */
    static final int MAX_RETRIES = 5;

    private static final String AGOTADO = "Max retries reached";

    private final WebhookEventJpaRepository events;
    private final ProcessWebhookUseCase webhooks;
    private final int batchSize;

    public WebhookReprojectionWorker(WebhookEventJpaRepository events, ProcessWebhookUseCase webhooks,
                                     @Value("${bff.reconciliation.webhook-batch:50}") int batchSize) {
        this.events = events;
        this.webhooks = webhooks;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${bff.reconciliation.webhooks-ms:1800000}",
            initialDelayString = "${bff.reconciliation.initial-delay-ms:60000}")
    public void reprojectPendingEvents() {
        List<WebhookEventEntity> pendientes = events
                .findByProcessedFalseAndRetryCountLessThanOrderByCreatedAtAsc(MAX_RETRIES)
                .stream()
                .limit(batchSize)
                .toList();
        if (pendientes.isEmpty()) {
            return;
        }
        int proyectados = 0;
        for (WebhookEventEntity evento : pendientes) {
            try {
                // Llamada a traves del proxy: dentro del caso de uso una llamada interna no
                // abriria transaccion y cada proyeccion quedaria a medias.
                webhooks.reproject(evento);
                proyectados++;
            } catch (KiraNotConfiguredException e) {
                // No es culpa del evento: la fila sigue pendiente y se reintenta con credenciales.
                log.warn("Reproyeccion de webhooks omitida: {}", e.getMessage());
                return;
            } catch (Exception e) {
                evento.setRetryCount(evento.getRetryCount() + 1);
                boolean agotado = evento.getRetryCount() >= MAX_RETRIES;
                evento.setProcessingError(agotado ? AGOTADO : truncate(e.getMessage()));
                events.save(evento);
                if (agotado) {
                    // Ultimo sitio donde se ve la causa: a partir de aqui la fila solo dice AGOTADO.
                    log.error("El evento {} agota los {} intentos y queda como fallido definitivo."
                            + " Ultima causa: {}", evento.getEventId(), MAX_RETRIES, e.getMessage());
                } else {
                    log.error("El evento {} sigue sin poder proyectarse (intento {}/{}): {}",
                            evento.getEventId(), evento.getRetryCount(), MAX_RETRIES, e.getMessage());
                }
            }
        }
        log.info("Reproyeccion de webhooks: {} pendientes, {} proyectados.",
                pendientes.size(), proyectados);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
