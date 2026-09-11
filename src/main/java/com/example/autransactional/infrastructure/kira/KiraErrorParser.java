package com.example.autransactional.infrastructure.kira;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Kira convive hoy con varias formas de error: {error, details}, {message},
 * {code, error, message} y {statusCode, error, message}. La documentacion advierte
 * explicitamente de no escribir un parser que asuma una sola forma.
 */
@Component
public class KiraErrorParser {

    private final ObjectMapper objectMapper;

    public KiraErrorParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public KiraApiException parse(int statusCode, String body) {
        String code = null;
        String message = null;

        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                code = text(root, "code");
                message = firstNonBlank(text(root, "message"), text(root, "error"), text(root, "detail"));
                if (message == null && root.hasNonNull("details")) {
                    message = root.get("details").toString();
                }
            } catch (Exception ignored) {
                // Cuerpo no-JSON: nos quedamos con el texto crudo.
            }
        }

        if (message == null || message.isBlank()) {
            message = (body == null || body.isBlank()) ? "Error HTTP " + statusCode : body;
        }
        return new KiraApiException(statusCode, code, message, body);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
