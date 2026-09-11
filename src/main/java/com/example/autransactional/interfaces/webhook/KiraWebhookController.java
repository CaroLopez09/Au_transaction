package com.example.autransactional.interfaces.webhook;

import com.example.autransactional.application.webhook.ProcessWebhookUseCase;
import com.example.autransactional.infrastructure.kira.KiraWebhookVerifier;
import org.slf4j.Logger;
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

/**
 * Ingress de eventos de Kira.
 *
 * Kira entrega una sola vez, sin reintentos, y aborta a los 30 segundos. Por eso este
 * controlador solo hace dos cosas: verificar la firma sobre los bytes crudos y encolar.
 * Todo lo demas ocurre despues de haber respondido.
 *
 * Se responde 2xx incluso ante un evento desconocido: un 4xx no provoca reintento, solo
 * pierde el evento.
 */
@Tag(name = "6. Webhooks de Kira", description = "Ingress firmado con HMAC. Entrega unica, sin reintentos.")
@RestController
@RequestMapping("/api/webhooks")
public class KiraWebhookController {

    private static final Logger log = LoggerFactory.getLogger(KiraWebhookController.class);

    private final ProcessWebhookUseCase processWebhook;
    private final KiraWebhookVerifier verifier;

    public KiraWebhookController(ProcessWebhookUseCase processWebhook, KiraWebhookVerifier verifier) {
        this.processWebhook = processWebhook;
        this.verifier = verifier;
    }

    @PostMapping(value = "/kira", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = KiraWebhookVerifier.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] rawBody) {

        if (!verifier.isConfigured()) {
            log.error("Llego un webhook pero no hay secreto de firma configurado (KIRA_WEBHOOK_SECRET).");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "webhook_secret_not_configured"));
        }

        if (!verifier.verify(rawBody, signature)) {
            log.warn("Webhook rechazado: firma x-signature-sha256 invalida ({} bytes).",
                    rawBody == null ? 0 : rawBody.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_signature"));
        }

        // Se convierte a texto solo despues de validar la firma sobre los bytes originales.
        processWebhook.enqueue(new String(rawBody, StandardCharsets.UTF_8));

        return ResponseEntity.ok(Map.of("status", "received"));
    }
}
