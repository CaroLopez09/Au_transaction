package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class QuotationTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private Quotation cotizacion(Instant vence) {
        return new Quotation("q-1", TENANT, "va-1", "rec-1", QuotationRail.WIRE_DOMESTIC,
                new BigDecimal("1000.00"), FeeBreakdown.standard(), vence);
    }

    private Quotation cotizada() {
        Quotation q = cotizacion(Instant.now().plusSeconds(Quotation.TTL_SECONDS));
        q.applyKiraQuote("qt_1", Instant.now().plusSeconds(900), new BigDecimal("1030.00"),
                new BigDecimal("1000.00"), "USD", BigDecimal.ONE,
                FeeBreakdown.fromTotals(new BigDecimal("15.00"), new BigDecimal("15.00")),
                true, "kraken", "{}");
        return q;
    }

    @Test
    void elDebitoEsLoPrometidoMasLasComisiones() {
        Quotation q = cotizada();

        assertEquals(0, new BigDecimal("1000.00").compareTo(q.getOriginAmount()));
        assertEquals(0, new BigDecimal("1030.00").compareTo(q.getTotalDebitAmount()));
        assertTrue(q.hasConsistentTotals());
    }

    @Test
    void unBrutoQueNoCuadraSeDetecta() {
        // Si el importe mostrado y el debitado no son el mismo numero, hay que verlo.
        Quotation q = cotizacion(Instant.now().plusSeconds(900));
        q.applyKiraQuote("qt_1", null, new BigDecimal("1099.00"), new BigDecimal("1000.00"),
                "USD", BigDecimal.ONE, FeeBreakdown.standard(), true, "kraken", null);

        assertFalse(q.hasConsistentTotals());
    }

    @Test
    void unPreviewNoEsRedimible() {
        Quotation q = cotizacion(Instant.now().plusSeconds(900));

        assertThrows(DomainException.class,
                () -> q.applyKiraQuote(null, null, null, null, null, null, null, true, null, null));
    }

    @Test
    void unaCotizacionVencidaNoSeReutiliza() {
        Quotation q = cotizacion(Instant.now().minusSeconds(1));

        assertThrows(DomainException.class, () -> q.assertUsable(Instant.now()));
        // Consultarla ya la deja marcada como vencida.
        assertEquals(QuotationStatus.EXPIRED, q.getStatus());
    }

    @Test
    void sinSaldoNoSeRedime() {
        Quotation q = cotizacion(Instant.now().plusSeconds(900));
        q.applyKiraQuote("qt_1", null, new BigDecimal("1030.00"), new BigDecimal("1000.00"), "USD",
                BigDecimal.ONE, FeeBreakdown.standard(), false, "kraken", null);

        var e = assertThrows(DomainException.class, () -> q.assertRedeemable(Instant.now()));
        assertTrue(e.getMessage().contains("Saldo insuficiente"), e.getMessage());
    }

    @Test
    void unaCotizacionYaEjecutadaNoSeVuelveAUsar() {
        Quotation q = cotizada();
        q.markExecuted();

        assertThrows(DomainException.class, () -> q.assertUsable(Instant.now()));
    }

    @Test
    void elContadorNuncaEsNegativo() {
        assertEquals(0, cotizacion(Instant.now().minusSeconds(60)).secondsToExpiry(Instant.now()));
        assertTrue(cotizada().secondsToExpiry(Instant.now()) > 800);
    }

    @Test
    void detectaLaTasaDeContingencia() {
        Quotation q = cotizacion(Instant.now().plusSeconds(900));
        q.applyKiraQuote("qt_1", null, null, null, null, null, null, true, "fallback_at_peg", null);

        assertTrue(q.usesFallbackRate());
    }

    @Test
    void elPagoHeredaLasComisionesRealesDeLaCotizacion() {
        Quotation q = cotizada();
        Payout pago = new Payout("p-1", TENANT, "usr_1", "va-1", "rec-1",
                Money.of(new BigDecimal("1000.00"), "USD"), FeeBreakdown.standard(),
                IdempotencyKey.newKey(), "maker-1");

        pago.attachQuotation(q, Instant.now());

        assertEquals("q-1", pago.getQuotationId());
        assertEquals(0, new BigDecimal("30.00").compareTo(pago.getFees().totalFee()));
        // Atar no consume: la cotizacion se gasta al enviar el pago a Kira.
        assertEquals(QuotationStatus.ACTIVE, q.getStatus());
    }

    @Test
    void noSeAtaUnaCotizacionDeOtroDestinatario() {
        Quotation ajena = new Quotation("q-9", TENANT, "va-1", "rec-OTRO", QuotationRail.WIRE_DOMESTIC,
                new BigDecimal("1000.00"), FeeBreakdown.standard(), Instant.now().plusSeconds(900));
        Payout pago = new Payout("p-1", TENANT, "usr_1", "va-1", "rec-1",
                Money.of(new BigDecimal("1000.00"), "USD"), FeeBreakdown.standard(),
                IdempotencyKey.newKey(), "maker-1");

        assertThrows(DomainException.class, () -> pago.attachQuotation(ajena, Instant.now()));
    }

    @Test
    void unaCotizacionVencidaBloqueaLaAprobacionDelPago() {
        Payout pago = new Payout("p-1", TENANT, "usr_1", "va-1", "rec-1",
                Money.of(new BigDecimal("1000.00"), "USD"), FeeBreakdown.standard(),
                IdempotencyKey.newKey(), "maker-1");
        Instant ahora = Instant.now();
        pago.attachQuotation("q-1", ahora.minus(Duration.ofSeconds(1)));

        assertThrows(DomainException.class, () -> pago.approve("approver-2", ahora));
    }
}
