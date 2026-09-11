package com.example.autransactional.domain.compliance.identity;

import com.example.autransactional.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class VerificationSessionTest {

    private final VerificationThresholds umbrales = new VerificationThresholds(80, 85, 70);

    private VerificationSession sesion() {
        return new VerificationSession("v-1", TenantId.of("juriscop"), "corr-1",
                Instant.now().plusSeconds(1800));
    }

    private VerificationSession sesionCompleta() {
        VerificationSession s = sesion();
        Instant ahora = Instant.now();
        s.issueChallenge(NumberChallenge.issue("c-1", ahora.plusSeconds(120)), ahora);
        s.markChallengePassed(0.94, ahora);
        s.attachLivenessSession("l-1", ahora);
        s.markLivenessPassed(96.5, ahora);
        return s;
    }

    @Test
    void laPruebaDeVidaExigeHaberSuperadoElRetoDeVoz() {
        VerificationSession s = sesion();

        var e = assertThrows(IdentityException.class,
                () -> s.attachLivenessSession("l-1", Instant.now()));

        assertEquals(IdentityErrorCode.VERIFICATION_INCOMPLETE, e.getCode());
    }

    @Test
    void unRetoDeOtraSesionNoSirve() {
        VerificationSession s = sesionCompleta();

        var e = assertThrows(IdentityException.class,
                () -> s.assertReadyForValidation("c-de-otra-sesion", "l-1", Instant.now()));

        assertEquals(IdentityErrorCode.SESSION_MISMATCH, e.getCode());
    }

    @Test
    void unLivenessDeOtraSesionNoSirve() {
        // Es el ataque que describe la recomendacion S-2: combinar un liveness valido
        // de una sesion con los documentos de otra.
        VerificationSession s = sesionCompleta();

        var e = assertThrows(IdentityException.class,
                () -> s.assertReadyForValidation("c-1", "l-de-otra-sesion", Instant.now()));

        assertEquals(IdentityErrorCode.SESSION_MISMATCH, e.getCode());
    }

    @Test
    void conLasTresPruebasDeLaMismaSesionSiValida() {
        VerificationSession s = sesionCompleta();

        assertDoesNotThrow(() -> s.assertReadyForValidation("c-1", "l-1", Instant.now()));
    }

    @Test
    void variosRostrosEnElDocumentoRechazan() {
        VerificationSession s = sesionCompleta();

        var veredicto = s.decide(new FaceMatch(99, 2), null, umbrales, Instant.now());

        assertEquals(VerificationVerdict.REJECTED, veredicto);
        assertEquals(IdentityErrorCode.MULTIPLE_FACES, s.getFailureCode());
    }

    @Test
    void unDocumentoSinRostroLegibleRechaza() {
        VerificationSession s = sesionCompleta();

        var veredicto = s.decide(new FaceMatch(0, 0), null, umbrales, Instant.now());

        assertEquals(VerificationVerdict.REJECTED, veredicto);
        assertEquals(IdentityErrorCode.DOCUMENT_UNREADABLE, s.getFailureCode());
    }

    @Test
    void elVeredictoUsaElPuntajeDeLivenessGuardadoNoElQueLlegue() {
        VerificationSession s = sesionCompleta();

        var veredicto = s.decide(new FaceMatch(90, 1), null, umbrales, Instant.now());

        assertEquals(VerificationVerdict.APPROVED, veredicto);
        assertEquals(96.5, s.getLivenessScore());
    }

    @Test
    void unaSesionYaResueltaNoSeVuelveADecidir() {
        VerificationSession s = sesionCompleta();
        s.decide(new FaceMatch(90, 1), null, umbrales, Instant.now());

        assertThrows(IdentityException.class,
                () -> s.decide(new FaceMatch(99, 1), null, umbrales, Instant.now()));
    }

    @Test
    void unaSesionVencidaNoAdmiteNadaMas() {
        VerificationSession s = new VerificationSession("v-2", TenantId.of("t"), "c",
                Instant.now().minusSeconds(1));

        var e = assertThrows(IdentityException.class,
                () -> s.issueChallenge(NumberChallenge.issue("c-9", Instant.now()), Instant.now()));

        assertEquals(IdentityErrorCode.SESSION_NOT_FOUND, e.getCode());
    }
}
