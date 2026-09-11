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
    void returnedYCancelledResuelvenEnFailed() {
        // No existen como estado del recurso: el GET devuelve FAILED en ambos casos.
        assertEquals(PayoutStatus.FAILED, PayoutStatus.fromWire("returned"));
        assertEquals(PayoutStatus.FAILED, PayoutStatus.fromWire("cancelled"));
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
