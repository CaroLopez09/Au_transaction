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
                JsonNode errorNode = root.get("error");
                if (errorNode != null && errorNode.isObject()) {
                    // Forma anidada: {error: {code, message, details}}
                    code = text(errorNode, "code");
                    message = text(errorNode, "message");
                    message = appendDetails(message, errorNode.get("details"));
                } else {
                    // Forma plana: {error: "texto", details: [...]} o {message}/{detail}.
                    code = text(root, "code");
                    message = firstNonBlank(text(root, "message"), text(root, "error"), text(root, "detail"));
                    message = appendDetails(message, root.get("details"));
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

    /**
     * 'details' llega como objeto (sin info util) o como arreglo de {path, message, code} con el
     * campo real que fallo. Sin esto, un 400 de validacion siempre se ve como "Invalid request
     * data" sin decir cual campo. Se ignora si esta vacio o no aporta nada nuevo.
     */
    private static String appendDetails(String message, JsonNode details) {
        if (details == null || details.isNull()) {
            return message;
        }
        String detailText;
        if (details.isArray() && !details.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : details) {
                String path = text(item, "path");
                String itemMessage = text(item, "message");
                if (itemMessage == null) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(path != null && !path.isBlank() ? path + ": " + itemMessage : itemMessage);
            }
            detailText = sb.isEmpty() ? null : sb.toString();
        } else if (details.isObject() && !details.isEmpty()) {
            detailText = details.toString();
        } else {
            detailText = null;
        }
        if (detailText == null) {
            return message;
        }
        return message == null || message.isBlank() ? detailText : message + " (" + detailText + ")";
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
