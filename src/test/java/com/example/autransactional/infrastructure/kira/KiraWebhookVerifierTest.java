package com.example.autransactional.infrastructure.kira;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class KiraWebhookVerifierTest {

    private static final String SECRET = "un-secreto-de-firma-de-kira";

    private KiraWebhookVerifier verifier(String secret) {
        return new KiraWebhookVerifier(new KiraProperties(
                "https://api.balampay.com/sandbox", "k", "c", "p", "2026-06-01",
                secret, null, 3600, 300, 5000, 30000, "jp_morgan", true));
    }

    /** Firma de referencia calculada con HMAC-SHA256 hex sobre los bytes exactos del cuerpo. */
    private String sign(byte[] body, String secret) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void aceptaUnaFirmaValidaSobreLosBytesCrudos() throws Exception {
        byte[] body = "{\"event\":\"payout.completed\",\"data\":{\"event_id\":\"e1\"}}"
                .getBytes(StandardCharsets.UTF_8);

        assertTrue(verifier(SECRET).verify(body, sign(body, SECRET)));
    }

    @Test
    void rechazaSiElCuerpoCambiaUnSoloByte() throws Exception {
        byte[] original = "{\"amount\":\"100.00\"}".getBytes(StandardCharsets.UTF_8);
        byte[] alterado = "{\"amount\":\"900.00\"}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier(SECRET).verify(alterado, sign(original, SECRET)));
    }

    @Test
    void reserializarElJsonInvalidaLaFirma() throws Exception {
        // Motivo por el que el controlador firma byte[] y no un objeto ya deserializado.
        byte[] original = "{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] reserializado = "{\"b\": 2, \"a\": 1}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier(SECRET).verify(reserializado, sign(original, SECRET)));
    }

    @Test
    void duranteLaRotacionAceptaTambienElSecretoAnterior() throws Exception {
        // Kira firma con el secreto viejo cerca de un minuto tras rotarlo.
        var rotando = new KiraWebhookVerifier(new KiraProperties(
                "https://api.balampay.com/sandbox", "k", "c", "p", "2026-06-01",
                "secreto-nuevo", SECRET, 3600, 300, 5000, 30000, "jp_morgan", true));
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertTrue(rotando.verify(body, sign(body, SECRET)));
        assertTrue(rotando.verify(body, sign(body, "secreto-nuevo")));
        assertFalse(rotando.verify(body, sign(body, "otro")));
    }

    @Test
    void rechazaConOtroSecreto() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier(SECRET).verify(body, sign(body, "secreto-distinto")));
    }

    @Test
    void rechazaSinCabeceraDeFirma() {
        assertFalse(verifier(SECRET).verify("{}".getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    void sinSecretoConfiguradoNoValidaNada() {
        assertFalse(verifier("").isConfigured());
        assertFalse(verifier("").verify("{}".getBytes(StandardCharsets.UTF_8), "loquesea"));
    }
}
