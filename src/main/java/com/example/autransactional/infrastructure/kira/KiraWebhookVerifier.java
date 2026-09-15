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

    /**
     * Acepta la firma del secreto vigente o, si esta configurado, la del anterior: al rotar el
     * secreto, Kira sigue firmando con el viejo durante cerca de un minuto y no hay ventana en la
     * que acepte ambos (webhooks/overview). Tras la rotacion se borra KIRA_WEBHOOK_SECRET_PREVIOUS.
     */
    public boolean verify(byte[] rawBody, String signatureHeader) {
        if (!isConfigured() || signatureHeader == null || rawBody == null) {
            return false;
        }
        byte[] received = signatureHeader.trim().getBytes(StandardCharsets.UTF_8);
        if (matches(rawBody, properties.webhookSecret(), received)) {
            return true;
        }
        String previous = properties.webhookSecretPrevious();
        return previous != null && !previous.isBlank() && matches(rawBody, previous, received);
    }

    private static boolean matches(byte[] rawBody, String secret, byte[] received) {
        return MessageDigest.isEqual(hmacHex(rawBody, secret).getBytes(StandardCharsets.UTF_8), received);
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
