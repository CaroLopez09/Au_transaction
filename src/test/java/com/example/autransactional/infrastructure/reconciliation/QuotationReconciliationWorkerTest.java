package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.treasury.FeeBreakdown;
import com.example.autransactional.domain.treasury.Quotation;
import com.example.autransactional.domain.treasury.QuotationRail;
import com.example.autransactional.domain.treasury.QuotationRepository;
import com.example.autransactional.domain.treasury.QuotationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Una cotizacion vencida que sigue apareciendo como ACTIVE es un precio que el portal ofrece y
 * que ya no se puede redimir. El vencimiento es local: no hace falta preguntar a Kira.
 */
class QuotationReconciliationWorkerTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final QuotationRepository quotations = mock(QuotationRepository.class);
    private final QuotationReconciliationWorker worker = new QuotationReconciliationWorker(quotations);

    private Quotation vencida(String id) {
        return new Quotation(id, TENANT, "va-1", "rec-1", QuotationRail.WIRE_DOMESTIC,
                new BigDecimal("1000.00"), FeeBreakdown.standard(), Instant.now().minusSeconds(1));
    }

    @Test
    void unaCotizacionActivaYVencidaQuedaMarcadaComoExpirada() {
        Quotation q = vencida("q-1");
        when(quotations.findActiveExpiredBefore(any())).thenReturn(List.of(q));

        worker.expireStaleQuotations();

        assertEquals(QuotationStatus.EXPIRED, q.getStatus());
        verify(quotations).save(q);
    }

    @Test
    void sinCotizacionesVencidasNoSeGuardaNada() {
        when(quotations.findActiveExpiredBefore(any())).thenReturn(List.of());

        worker.expireStaleQuotations();

        verify(quotations, never()).save(any());
    }

    @Test
    void unFalloAlGuardarUnaNoDetieneALasDemas() {
        Quotation rota = vencida("q-1");
        Quotation buena = vencida("q-2");
        when(quotations.findActiveExpiredBefore(any())).thenReturn(List.of(rota, buena));
        when(quotations.save(rota)).thenThrow(new IllegalStateException("base caida"));

        worker.expireStaleQuotations();

        assertEquals(QuotationStatus.EXPIRED, buena.getStatus());
        verify(quotations).save(buena);
    }
}
