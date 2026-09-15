package com.example.autransactional.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Metricas de la integracion con Kira (arquitectura §5): latencia y errores del proveedor, y
 * webhooks recibidos, rechazados y sin proyectar. Las etiquetas nunca llevan ids ni datos de
 * una empresa: todo segmento de ruta que no sea fijo se sustituye por {id}.
 */
@Component
public class IntegrationMetrics {

    /** Segmentos fijos de las rutas de Kira que usa KiraApiClient; cualquier otro es un id. */
    private static final Set<String> LITERAL_SEGMENTS = Set.of("v1", "auth", "users", "liveness-link",
            "virtual-accounts", "balance", "deposits", "simulate-deposit", "payout", "preview", "payouts",
            "recipients", "quotations", "rfis", "items", "documents", "ubo-link", "countries", "versioning",
            "upgrade");

    private final MeterRegistry registry;

    public IntegrationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** kira.api.requests: duracion por metodo, ruta normalizada y resultado (codigo HTTP o io_error). */
    public void recordKiraCall(String method, String path, String outcome, long nanos) {
        Timer.builder("kira.api.requests")
                .description("Llamadas del BFF a la API de Kira")
                .tag("method", method)
                .tag("route", route(path))
                .tag("outcome", outcome)
                .register(registry)
                .record(java.time.Duration.ofNanos(nanos));
    }

    /** kira.webhooks.received: received, duplicate, invalid_signature, invalid_json o not_configured. */
    public void webhookReceived(String result) {
        registry.counter("kira.webhooks.received", "result", result).increment();
    }

    /** kira.webhooks.projection.failures: eventos guardados cuya proyeccion fallo (quedan para reintento). */
    public void webhookProjectionFailed(String eventType) {
        registry.counter("kira.webhooks.projection.failures", "event", eventType == null ? "unknown" : eventType)
                .increment();
    }

    static String route(String path) {
        String withoutQuery = path == null ? "" : path.split("\\?", 2)[0];
        StringBuilder route = new StringBuilder();
        for (String segment : withoutQuery.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            route.append('/').append(LITERAL_SEGMENTS.contains(segment) ? segment : "{id}");
        }
        return route.isEmpty() ? "/" : route.toString();
    }
}
