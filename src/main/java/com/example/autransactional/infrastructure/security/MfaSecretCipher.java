package com.example.autransactional.infrastructure.security;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra el secreto TOTP en reposo (AES-256-GCM). Con la base de datos sola no se pueden generar
 * codigos: hace falta tambien BFF_MFA_ENCRYPTION_KEY, que vive en el gestor de secretos.
 *
 * Formato: base64(iv de 12 bytes || texto cifrado con etiqueta).
 */
@Component
public class MfaSecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public MfaSecretCipher(BffSecurityProperties properties) {
        this.key = new SecretKeySpec(sha256(keyMaterial(properties)), "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar el secreto MFA", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo descifrar el secreto MFA (cambio la clave?)", e);
        }
    }

    /**
     * En cert y prod la clave es obligatoria (RequiredSecretsValidator). En dev, si falta, se deriva
     * del secreto del JWT para no pedir una variable mas en local.
     */
    private static String keyMaterial(BffSecurityProperties properties) {
        String explicit = properties.mfaEncryptionKey();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return "mfa-at-rest:" + (properties.jwtSecret() == null ? "" : properties.jwtSecret());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
