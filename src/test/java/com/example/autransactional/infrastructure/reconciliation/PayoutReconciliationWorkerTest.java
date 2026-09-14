package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.treasury.FeeBreakdown;
import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.domain.treasury.PayoutStatus;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraNotConfiguredException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Kira entrega cada webhook una sola vez: si se pierde, el pago se queda para siempre en el
 * estado que tenia. Este worker vuelve a preguntar por el recurso, que es la autoridad final.
 */
class PayoutReconciliationWorkerTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final ObjectMapper mapper = new ObjectMapper();
    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final PayoutReconciliationWorker worker = new PayoutReconciliationWorker(payouts, kira, 50);

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }

    private Payout enVuelo(String id, String kiraId) {
        Payout p = new Payout(id, TENANT, "usr_1", "va-1", "rec-1",
                Money.of(new BigDecimal("100.00"), "USD"), FeeBreakdown.standard(),
                IdempotencyKey.newKey(), "maker-1");
        p.approve("approver-1", Instant.now());
        p.markAsSubmitted(kiraId, "created");
        return p;
    }

    @Test
    void unPagoEnVueloSeActualizaConElEstadoDelRecurso() {
        Payout pago = enVuelo("p-1", "pay_1");
        when(payouts.findInFlight(anyInt())).thenReturn(List.of(pago));
        when(kira.getPayout("pay_1")).thenReturn(json("""
                { "payout_id": "pay_1", "status": "COMPLETED", "reference_number": "IMAD-20260911-001",
                  "payment_method": "wire" }
                """));

        worker.reconcile();

        assertEquals(PayoutStatus.COMPLETED, pago.getStatus());
        assertEquals("IMAD-20260911-001", pago.getReferenceNumber());
        verify(payouts).save(pago);
    }

    @Test
    void unFalloEnUnPagoNoDetieneElLote() {
        Payout roto = enVuelo("p-1", "pay_roto");
        Payout bueno = enVuelo("p-2", "pay_2");
        when(payouts.findInFlight(anyInt())).thenReturn(List.of(roto, bueno));
        when(kira.getPayout("pay_roto")).thenThrow(new IllegalStateException("timeout"));
        when(kira.getPayout("pay_2")).thenReturn(json("{\"status\":\"FAILED\",\"error_code\":\"va-payout-bank-returned\"}"));

        worker.reconcile();

        assertEquals(PayoutStatus.FAILED, bueno.getStatus());
        assertEquals("va-payout-bank-returned", bueno.getErrorCode());
        verify(payouts).save(bueno);
    }

    @Test
    void sinCredencialesDeKiraSeCortaElLoteSinTocarNada() {
        when(payouts.findInFlight(anyInt())).thenReturn(List.of(enVuelo("p-1", "pay_1"), enVuelo("p-2", "pay_2")));
        when(kira.getPayout(anyString())).thenThrow(new KiraNotConfiguredException("Falta KIRA_API_KEY."));

        worker.reconcile();

        verify(kira, times(1)).getPayout(anyString());
        verify(payouts, never()).save(any());
    }

    @Test
    void sinPagosEnVueloNoSeLlamaAKira() {
        when(payouts.findInFlight(anyInt())).thenReturn(List.of());

        worker.reconcile();

        verifyNoInteractions(kira);
    }

    @Test
    void unEstadoTardioNoRevierteUnPagoYaTerminal() {
        Payout pago = enVuelo("p-1", "pay_1");
        pago.applyRemoteStatus(PayoutStatus.COMPLETED, null);
        when(payouts.findInFlight(anyInt())).thenReturn(List.of(pago));
        when(kira.getPayout("pay_1")).thenReturn(json("{\"status\":\"PROCESSING\"}"));

        worker.reconcile();

        assertEquals(PayoutStatus.COMPLETED, pago.getStatus());
    }
}
