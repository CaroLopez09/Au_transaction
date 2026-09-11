package com.example.autransactional.application.webhook;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KiraWebhookEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private KiraWebhookEnvelope parse(String json) throws Exception {
        return KiraWebhookEnvelope.from(mapper.readTree(json));
    }

    @Test
    void leeLaEnvolturaPlana() throws Exception {
        var envelope = parse("""
                {
                  "event": "virtual_account.deposit_funds_received",
                  "data": {
                    "event_id": "491e0d6e-a5e1-4158-a331-db8accc80a57",
                    "status": "completed",
                    "virtual_account_id": "f236ae11"
                  }
                }
                """);

        assertEquals("virtual_account.deposit_funds_received", envelope.eventName());
        assertEquals("491e0d6e-a5e1-4158-a331-db8accc80a57", envelope.eventId());
        assertFalse(envelope.nested());
        assertEquals("completed", envelope.rawStatus());
        assertEquals("f236ae11", envelope.text("virtual_account_id"));
    }

    @Test
    void desanidaLaEnvolturaV2DePayoutStatusChanged() throws Exception {
        var envelope = parse("""
                {
                  "event": "payout.status_changed",
                  "data": {
                    "event_id": "f6e3c92c-43b5-49e5-8545-de31dc1105c9",
                    "event_type": "payout.status_changed",
                    "created_at": "2026-05-23T00:37:56.874Z",
                    "data": {
                      "status": "IN_REVIEW",
                      "previous_status": "PROCESSING",
                      "payout_id": "e2503e1d-6a42-4602-bc83-4eddc15a18aa"
                    }
                  }
                }
                """);

        assertTrue(envelope.nested());
        // event_id sigue en data.event_id, nunca en data.data ni en la raiz.
        assertEquals("f6e3c92c-43b5-49e5-8545-de31dc1105c9", envelope.eventId());
        assertEquals("IN_REVIEW", envelope.rawStatus());
        assertEquals("e2503e1d-6a42-4602-bc83-4eddc15a18aa", envelope.text("payout_id"));
    }

    @Test
    void noHayEventIdEnLaRaiz() throws Exception {
        var envelope = parse("{\"event\":\"payout.created\",\"event_id\":\"raiz\",\"data\":{}}");

        assertNull(envelope.eventId());
    }
}
