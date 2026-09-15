package com.example.autransactional.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TotpTest {

    /** Semilla SHA-1 del apendice B de la RFC 6238 ("12345678901234567890"). */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void reproduceLosVectoresDeLaRfc6238() {
        // Los vectores son de 8 digitos; los 6 ultimos son el codigo de 6.
        assertTrue(Totp.verify(RFC_SECRET, "287082", Instant.ofEpochSecond(59)).isPresent());
        assertTrue(Totp.verify(RFC_SECRET, "081804", Instant.ofEpochSecond(1111111109)).isPresent());
        assertTrue(Totp.verify(RFC_SECRET, "005924", Instant.ofEpochSecond(1234567890)).isPresent());
        assertTrue(Totp.verify(RFC_SECRET, "279037", Instant.ofEpochSecond(2000000000)).isPresent());
    }

    @Test
    void toleraUnPasoDeDesfaseYNoMas() {
        Instant t = Instant.ofEpochSecond(1111111109);
        assertTrue(Totp.verify(RFC_SECRET, "081804", t.plusSeconds(30)).isPresent());
        assertFalse(Totp.verify(RFC_SECRET, "081804", t.plusSeconds(90)).isPresent());
    }

    @Test
    void rechazaFormatosQueNoSonSeisDigitos() {
        Instant t = Instant.ofEpochSecond(59);
        assertFalse(Totp.verify(RFC_SECRET, "28708", t).isPresent());
        assertFalse(Totp.verify(RFC_SECRET, "abcdef", t).isPresent());
        assertFalse(Totp.verify(RFC_SECRET, null, t).isPresent());
    }

    @Test
    void elSecretoNuevoEsBase32DeCientoSesentaBits() {
        String secret = Totp.newSecret();
        assertEquals(32, secret.length());
        assertTrue(secret.matches("[A-Z2-7]+"));
        assertEquals(20, Totp.decodeBase32(secret).length);
    }

    @Test
    void laUriLaEntiendenLasAppsAutenticadoras() {
        String uri = Totp.otpauthUri("AU Transactional", "ana@juriscop.test", "JBSWY3DPEHPK3PXP");
        assertEquals("otpauth://totp/AU%20Transactional:ana%40juriscop.test?secret=JBSWY3DPEHPK3PXP"
                + "&issuer=AU%20Transactional&algorithm=SHA1&digits=6&period=30", uri);
    }
}
