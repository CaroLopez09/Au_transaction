package com.example.autransactional.domain.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VirtualAccountReadinessTest {

    @Test
    void approvedSinNumeroDeCuentaNoEstaListaParaFondos() {
        assertFalse(VirtualAccountReadiness.isFundsReady("approved", null, false));
    }

    @Test
    void elCentinelaDeActNoCuentaComoCuentaReal() {
        assertFalse(VirtualAccountReadiness.isFundsReady(
                "approved", VirtualAccountReadiness.ACT_PENDING_SENTINEL, false));
    }

    @Test
    void unNumeroDeCuentaRealSiLaHabilita() {
        assertTrue(VirtualAccountReadiness.isFundsReady("approved", "1234567890", false));
    }

    @Test
    void elEventoActivatedEsSenalSuficiente() {
        assertTrue(VirtualAccountReadiness.isFundsReady("approved", null, true));
    }

    @Test
    void unaCuentaRechazadaNuncaEstaLista() {
        assertFalse(VirtualAccountReadiness.isFundsReady("declined", "1234567890", false));
        assertFalse(VirtualAccountReadiness.isFundsReady("DEACTIVATED", "1234567890", false));
    }

    @Test
    void unaCuentaCongeladaNoMueveFondosAunqueSeHayaActivado() {
        assertFalse(VirtualAccountReadiness.isFundsReady("frozen", "1234567890", true));
    }
}
