package com.example.autransactional.domain.compliance.identity;

import com.example.autransactional.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VerificationThresholdsTest {

    private final VerificationThresholds umbrales = new VerificationThresholds(80, 85, 70);

    @Test
    void apruebaCuandoAmbosPuntajesSuperanElUmbral() {
        assertEquals(VerificationVerdict.APPROVED, umbrales.decide(96, 90));
    }

    @Test
    void unLivenessBajoRechazaAunqueElRostroCoincida() {
        // La prueba de vida es la que descarta foto impresa, pantalla o mascara:
        // si no se supera, la similitud facial es irrelevante.
        assertEquals(VerificationVerdict.REJECTED, umbrales.decide(40, 99));
    }

    @Test
    void laFranjaIntermediaVaARevisionManual() {
        assertEquals(VerificationVerdict.MANUAL_REVIEW, umbrales.decide(96, 75));
    }

    @Test
    void pordebajoDelPisoDeRevisionSeRechaza() {
        assertEquals(VerificationVerdict.REJECTED, umbrales.decide(96, 60));
    }

    @Test
    void unPisoDeRevisionPorEncimaDelUmbralEsIncoherente() {
        assertThrows(DomainException.class, () -> new VerificationThresholds(80, 70, 90));
    }
}
