package com.example.autransactional.domain.treasury;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayoutStatusTest {

    @Test
    void comparaSinDistinguirMayusculas() {
        assertEquals(PayoutStatus.CREATED, PayoutStatus.fromWire("created"));
        assertEquals(PayoutStatus.CREATED, PayoutStatus.fromWire("CREATED"));
        assertEquals(PayoutStatus.IN_REVIEW, PayoutStatus.fromWire("in_review"));
    }

    @Test
    void returnedResuelveEnFailed() {
        // Una devolucion bancaria no es un estado del recurso: el GET devuelve FAILED.
        assertEquals(PayoutStatus.FAILED, PayoutStatus.fromWire("returned"));
    }

    @Test
    void cancelledEsUnEstadoFinalPropio() {
        // docs.kirafin.ai/reference/payouts/values: "Stopped before it was sent", distinto de FAILED.
        assertEquals(PayoutStatus.CANCELLED, PayoutStatus.fromWire("CANCELLED"));
        assertEquals(PayoutStatus.CANCELLED, PayoutStatus.fromWire("canceled"));
        assertTrue(PayoutStatus.CANCELLED.isTerminal());
        assertFalse(PayoutStatus.CANCELLED.isInFlight());
    }

    @Test
    void unEstadoDesconocidoNoRompeYNoEsTerminal() {
        PayoutStatus status = PayoutStatus.fromWire("algo_que_no_existe_todavia");

        assertEquals(PayoutStatus.UNKNOWN, status);
        assertFalse(status.isTerminal());
    }

    @Test
    void kytPendingEInReviewSonNoTerminales() {
        assertFalse(PayoutStatus.KYT_PENDING.isTerminal());
        assertFalse(PayoutStatus.IN_REVIEW.isTerminal());
        assertTrue(PayoutStatus.KYT_PENDING.isInFlight());
    }
}
