package com.example.autransactional.interfaces.webhook;

import com.example.autransactional.application.webhook.ProcessWebhookUseCase;
import com.example.autransactional.infrastructure.kira.KiraWebhookVerifier;
import com.example.autransactional.infrastructure.observability.IntegrationMetrics;
import org.slf4j.Logger;
import tools.jackson.core.JacksonException;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Ingress de eventos de Kira.
 *
 * Kira corta a los 30 segundos y reintenta 4 veces (1, 5, 15 y 60 min) ante 408, 429, 5xx
 * o falta de respuesta. Por eso este controlador solo verifica la firma sobre los bytes
 * crudos y guarda el evento; la proyeccion ocurre despues de haber respondido.
 *
 * Se responde 2xx incluso ante un evento desconocido: un 4xx es la unica respuesta que Kira
 * no reintenta, asi que solo serviria para perder el evento.
 */
@Tag(name = "6. Webhooks de Kira", description = "Ingress firmado con HMAC. Kira reintenta 4 veces ante 5xx o timeout.")
@RestController
@RequestMapping("/api/webhooks")
public class KiraWebhookController {

    private static final Logger log = LoggerFactory.getLogger(KiraWebhookController.class);

    private final ProcessWebhookUseCase processWebhook;
    private final KiraWebhookVerifier verifier;
    private final IntegrationMetrics metrics;

    public KiraWebhookController(ProcessWebhookUseCase processWebhook, KiraWebhookVerifier verifier,
                                 IntegrationMetrics metrics) {
        this.metrics = metrics;
        this.processWebhook = processWebhook;
        this.verifier = verifier;
    }

    @PostMapping(value = "/kira", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = KiraWebhookVerifier.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] rawBody) {

        if (!verifier.isConfigured()) {
            log.error("Llego un webhook pero no hay secreto de firma configurado (KIRA_WEBHOOK_SECRET).");
            metrics.webhookReceived("not_configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "webhook_secret_not_configured"));
        }

        if (!verifier.verify(rawBody, signature)) {
            log.warn("Webhook rechazado: firma x-signature-sha256 invalida ({} bytes).",
                    rawBody == null ? 0 : rawBody.length);
            metrics.webhookReceived("invalid_signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_signature"));
        }

        // Se convierte a texto solo despues de validar la firma sobre los bytes originales.
        String payload = new String(rawBody, StandardCharsets.UTF_8);
        Optional<String> storedId;
        try {
            // Se guarda ANTES de responder: si la base falla sale un 5xx y Kira reintenta.
            storedId = processWebhook.record(payload);
        } catch (JacksonException malformed) {
            // Firmado pero ilegible: reintentarlo daria lo mismo, y un 4xx es lo unico que Kira no reintenta.
            log.error("Webhook con firma valida pero JSON ilegible ({} bytes).", rawBody.length);
            metrics.webhookReceived("invalid_json");
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_json"));
        }
        storedId.ifPresent(processWebhook::projectLater);
        metrics.webhookReceived(storedId.isPresent() ? "received" : "duplicate");

        return ResponseEntity.ok(Map.of("status", storedId.isPresent() ? "received" : "duplicate"));
    }
}
