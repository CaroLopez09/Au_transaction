package com.example.autransactional.domain.compliance.identity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class NumberChallengeTest {

    private NumberChallenge reto(Instant expiraEn) {
        return NumberChallenge.issue("c-1", expiraEn);
    }

    @Test
    void elNumeroEsDeCuatroDigitos() {
        assertTrue(reto(Instant.now().plusSeconds(120)).getNumber().matches("\\d{4}"));
    }

    @Test
    void aciertaConElNumeroCorrectoYLabiosDetectados() {
        var reto = reto(Instant.now().plusSeconds(120));

        reto.verify(reto.getNumber(), true, Instant.now());

        assertTrue(reto.isUsed());
    }

    @Test
    void audioCorrectoSinMovimientoLabialEsVideoPregrabado() {
        var reto = reto(Instant.now().plusSeconds(120));

        var e = assertThrows(IdentityException.class,
                () -> reto.verify(reto.getNumber(), false, Instant.now()));

        assertEquals(IdentityErrorCode.LIP_MOVEMENT_NOT_DETECTED, e.getCode());
        assertFalse(reto.isUsed());
    }

    @Test
    void unNumeroDistintoNoPasa() {
        var reto = reto(Instant.now().plusSeconds(120));
        String otro = reto.getNumber().equals("0000") ? "1111" : "0000";

        var e = assertThrows(IdentityException.class, () -> reto.verify(otro, true, Instant.now()));

        assertEquals(IdentityErrorCode.INVALID_SPOKEN_NUMBER, e.getCode());
    }

    @Test
    void unRetoVencidoNoAdmiteVerificacion() {
        var reto = reto(Instant.now().minusSeconds(1));

        var e = assertThrows(IdentityException.class,
                () -> reto.verify(reto.getNumber(), true, Instant.now()));

        assertEquals(IdentityErrorCode.NUMBER_CHALLENGE_EXPIRED, e.getCode());
    }

    @Test
    void unRetoNoSePuedeUsarDosVeces() {
        var reto = reto(Instant.now().plusSeconds(120));
        reto.verify(reto.getNumber(), true, Instant.now());

        var e = assertThrows(IdentityException.class,
                () -> reto.verify(reto.getNumber(), true, Instant.now()));

        assertEquals(IdentityErrorCode.NUMBER_CHALLENGE_ALREADY_USED, e.getCode());
    }

    @Test
    void alTercerFalloElRetoQuedaConsumido() {
        var reto = reto(Instant.now().plusSeconds(120));
        String otro = reto.getNumber().equals("0000") ? "1111" : "0000";
        Instant ahora = Instant.now();

        assertThrows(IdentityException.class, () -> reto.verify(otro, true, ahora));
        assertThrows(IdentityException.class, () -> reto.verify(otro, true, ahora));
        var e = assertThrows(IdentityException.class, () -> reto.verify(otro, true, ahora));

        assertEquals(IdentityErrorCode.MAX_ATTEMPTS_REACHED, e.getCode());
        assertTrue(reto.isUsed());
    }

    @Test
    void sinTranscripcionSeInformaFalloDeAudio() {
        var reto = reto(Instant.now().plusSeconds(120));

        var e = assertThrows(IdentityException.class, () -> reto.verify(null, true, Instant.now()));

        assertEquals(IdentityErrorCode.AUDIO_TRANSCRIPTION_FAILED, e.getCode());
    }
}
