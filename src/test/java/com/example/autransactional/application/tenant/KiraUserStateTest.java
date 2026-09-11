package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.TenantStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KiraUserStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private KiraUserState parse(String json) {
        return KiraUserState.from(mapper.readTree(json));
    }

    @Test
    void leeLaRespuestaDelAlta() {
        var state = parse("""
                {
                  "id": "usr_9c1f",
                  "status": "CREATED",
                  "verification_triggered": false,
                  "missing_fields": {
                    "general": ["business_type", "formation_date"],
                    "usa-virtual-accounts": ["expected_monthly_volume"]
                  },
                  "eligible_products": [
                    { "product_code": "usa-virtual-accounts", "eligible": false,
                      "missing_fields": ["expected_monthly_volume"] }
                  ]
                }
                """);

        assertEquals("usr_9c1f", state.kiraUserId());
        assertEquals(TenantStatus.CREATED, state.status());
        assertEquals(Boolean.FALSE, state.verificationTriggered());
        assertEquals(List.of("business_type", "formation_date", "expected_monthly_volume"),
                state.missingFields().forProduct(EligibleProduct.USA_VIRTUAL_ACCOUNTS));
        assertFalse(state.eligibleProducts().getFirst().eligible());
    }

    @Test
    void desenvuelveLaRespuestaConSobreData() {
        var state = parse("""
                { "message": "ok", "data": { "id": "usr_1", "status": "VERIFIED" } }
                """);

        assertEquals("usr_1", state.kiraUserId());
        assertEquals(TenantStatus.VERIFIED, state.status());
    }

    @Test
    void unGetSinVerificationTriggeredDejaElDatoIndefinido() {
        // Devolver false aqui borraria el hecho de que el KYB ya arranco.
        var state = parse("{ \"id\": \"usr_1\", \"status\": \"VERIFYING\" }");

        assertNull(state.verificationTriggered());
    }

    @Test
    void reconoceLaDiligenciaReforzada() {
        var state = parse("""
                { "id": "usr_1", "status": "REVIEW",
                  "eligible_products": [
                    { "product_code": "usa-virtual-accounts", "eligible": false,
                      "unsupported_reason": "enhanced_due_diligence_required" }
                  ] }
                """);

        assertTrue(state.eligibleProducts().getFirst().requiresEnhancedDueDiligence());
    }

    @Test
    void unEstadoDesconocidoNoRompeLaLectura() {
        var state = parse("{ \"id\": \"usr_1\", \"status\": \"algo_nuevo\" }");

        assertEquals(TenantStatus.CREATED, state.status());
        assertTrue(state.missingFields().isEmpty());
        assertTrue(state.eligibleProducts().isEmpty());
    }
}
