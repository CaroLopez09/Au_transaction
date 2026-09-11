package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.Rail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El riel del pago se deriva del account_type del destinatario. Si no coinciden, la API
 * falla al EJECUTAR el pago, no al cotizar: por eso se valida antes.
 */
class QuotationRailTest {

    @Test
    void cadaTipoDeCuentaTieneSusRieles() {
        assertEquals(QuotationRail.ACH_STANDARD, QuotationRail.defaultFor(Rail.ACH, null));
        assertEquals(QuotationRail.WIRE_DOMESTIC, QuotationRail.defaultFor(Rail.WIRE, null));
        assertEquals(QuotationRail.POLYGON, QuotationRail.defaultFor(Rail.WALLET, "polygon"));
    }

    @Test
    void unRielDeOtroTipoDeCuentaSeRechaza() {
        var e = assertThrows(DomainException.class,
                () -> QuotationRail.WIRE_DOMESTIC.assertMatches(Rail.ACH));

        assertTrue(e.getMessage().contains("ACH_STANDARD"), e.getMessage());
    }

    @Test
    void achAdmiteLosDosRitmos() {
        assertDoesNotThrow(() -> QuotationRail.ACH_STANDARD.assertMatches(Rail.ACH));
        assertDoesNotThrow(() -> QuotationRail.ACH_SAME_DAY.assertMatches(Rail.ACH));
        assertEquals(2, QuotationRail.validFor(Rail.ACH).size());
    }

    @Test
    void unaWalletSinRedNoSePuedeCotizar() {
        assertThrows(DomainException.class, () -> QuotationRail.defaultFor(Rail.WALLET, null));
        assertThrows(DomainException.class, () -> QuotationRail.fromNetwork("ethereum"));
    }

    @Test
    void soloLosRielesDeWalletLlevanRed() {
        assertEquals("solana", QuotationRail.SOLANA.network());
        assertNull(QuotationRail.WIRE_DOMESTIC.network());
    }
}
