package com.example.autransactional.application.webhook;

import tools.jackson.databind.JsonNode;

/**
 * Normaliza las dos envolturas activas de Kira.
 *
 *  - Plana:  { event, data { event_id, status, ... } }
 *  - V2 de payout.status_changed: { event, data { event_id, event_type, created_at,
 *                                   data { status EN MAYUSCULAS, previous_status, ... } } }
 *
 * En ambas el identificador para deduplicar esta en data.event_id; no existe a nivel raiz.
 */
public record KiraWebhookEnvelope(String eventName, String eventId, JsonNode payload, boolean nested) {

    public static KiraWebhookEnvelope from(JsonNode root) {
        JsonNode data = root.path("data");
        String eventName = root.path("event").asText(null);
        String eventId = data.path("event_id").asText(null);

        boolean nested = data.has("data") && data.get("data").isObject();
        if (nested && eventName == null) {
            eventName = data.path("event_type").asText(null);
        }
        JsonNode payload = nested ? data.get("data") : data;

        return new KiraWebhookEnvelope(eventName, eventId, payload, nested);
    }

    /** Estado tal como lo trae el evento. Siempre debe compararse sin distinguir mayusculas. */
    public String rawStatus() {
        return payload.path("status").asText(null);
    }

    public String text(String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
