package com.example.autransactional.infrastructure.kira;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifica la cabecera x-signature-sha256: HMAC-SHA256 en hexadecimal sobre los BYTES CRUDOS
 * del cuerpo, con el secreto de firma de la URL a la que llego la entrega.
 *
 * Dos reglas que la documentacion subraya:
 *  - no re-serializar el JSON antes de firmar (cambian espacios y orden de claves);
 *  - comparar en tiempo constante.
 */
@Component
public class KiraWebhookVerifier {

    public static final String SIGNATURE_HEADER = "x-signature-sha256";

    private static final String ALGORITHM = "HmacSHA256";

    private final KiraProperties properties;

    public KiraWebhookVerifier(KiraProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.webhookSecret() != null && !properties.webhookSecret().isBlank();
    }

    public boolean verify(byte[] rawBody, String signatureHeader) {
        if (!isConfigured() || signatureHeader == null || rawBody == null) {
            return false;
        }
        String expected = hmacHex(rawBody, properties.webhookSecret());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular el HMAC del webhook", e);
        }
    }
}
