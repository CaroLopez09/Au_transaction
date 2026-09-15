package com.example.autransactional.infrastructure.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Codigos de un solo uso por tiempo (RFC 6238): HMAC-SHA1, pasos de 30 s y 6 digitos, lo que
 * entienden Google Authenticator, Microsoft Authenticator y compania.
 *
 * Sin dependencias: el algoritmo es corto y una libreria para esto es superficie de mas.
 */
public final class Totp {

    private static final int DIGITS = 6;
    private static final long STEP_SECONDS = 30;
    /** Un paso de tolerancia a cada lado: relojes de telefono desfasados hasta ~30 s. */
    private static final int WINDOW = 1;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** 160 bits, el tamano que recomienda la RFC 4226 para SHA-1, en base32 sin relleno. */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    public static String otpauthUri(String issuer, String account, String secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /**
     * Devuelve el paso de tiempo que valida el codigo, para que quien llama pueda rechazar su
     * reutilizacion dentro de la misma ventana. Vacio si el codigo no vale.
     */
    public static OptionalLong verify(String secret, String code, Instant now) {
        if (code == null || !code.trim().matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        byte[] key = decodeBase32(secret);
        long current = now.getEpochSecond() / STEP_SECONDS;
        byte[] expectedBytes = code.trim().getBytes(StandardCharsets.US_ASCII);
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            byte[] candidate = codeAt(key, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(candidate, expectedBytes)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    static String codeAt(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular el codigo TOTP", e);
        }
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] decodeBase32(String value) {
        String clean = value.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int index = BASE32.indexOf(c);
            if (index < 0) {
                throw new IllegalArgumentException("Secreto TOTP con caracteres fuera de base32.");
            }
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
