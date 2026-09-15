package com.example.autransactional.domain.shared;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Tipo real de un archivo por sus primeros bytes (arquitectura §7: validar el MIME real, no el
 * que declara el navegador). Solo reconoce los formatos que Kira acepta.
 */
public final class FileSignature {

    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1");

    private FileSignature() {
    }

    /** MIME detectado, o null si no es ninguno de los formatos admitidos. */
    public static String detect(byte[] content) {
        if (content == null || content.length < 4) {
            return null;
        }
        if (startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (startsWith(content, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(content, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (content.length >= 12 && ascii(content, 0, 4).equals("RIFF") && ascii(content, 8, 12).equals("WEBP")) {
            return "image/webp";
        }
        if (content.length >= 12 && ascii(content, 4, 8).equals("ftyp")
                && HEIC_BRANDS.contains(ascii(content, 8, 12).toLowerCase(Locale.ROOT))) {
            return "image/heic";
        }
        return null;
    }

    /** El contenido es de verdad del tipo declarado. */
    public static boolean matches(String declaredMime, byte[] content) {
        String declared = declaredMime == null ? "" : declaredMime.trim().toLowerCase(Locale.ROOT);
        // image/jpg no es un MIME registrado, pero hay navegadores que lo mandan.
        if (declared.equals("image/jpg")) {
            declared = "image/jpeg";
        }
        return declared.equals(detect(content));
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static String ascii(byte[] content, int from, int to) {
        return new String(content, from, to - from, StandardCharsets.US_ASCII);
    }
}
