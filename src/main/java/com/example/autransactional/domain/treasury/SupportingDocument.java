package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.FileSignature;

import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Documento de soporte del pago.
 *
 * Aqui el archivo va SIEMPRE como data URI base64: a diferencia del KYB, este endpoint
 * no acepta URLs. Y el limite es de 3 MB por archivo, sobre un base64 que ya infla el
 * original un tercio.
 */
public record SupportingDocument(String type, String file) {

    public static final int MAX_FILE_BYTES = 3 * 1024 * 1024;
    public static final int MAX_DOCUMENTS = 2;
    private static final List<String> TYPES = List.of("invoice", "other");

    public SupportingDocument {
        if (type == null || !TYPES.contains(type.trim().toLowerCase(Locale.ROOT))) {
            throw new DomainException("El tipo de documento debe ser 'invoice' u 'other'.");
        }
        if (file == null || file.isBlank()) {
            throw new DomainException("El documento de soporte no puede estar vacio.");
        }
        if (!file.startsWith("data:")) {
            // Un https:// aqui se rechaza del lado de Kira, aunque si valga en el KYB.
            throw new DomainException("El documento debe ir como data URI base64, no como URL.");
        }
        if (file.length() > MAX_FILE_BYTES) {
            throw new DomainException("El documento supera los 3 MB permitidos.");
        }
        assertContentMatchesDeclaredType(file);
        type = type.trim().toLowerCase(Locale.ROOT);
    }

    /** Si el data URI dice PDF, PNG o JPEG, los primeros bytes tienen que serlo (arquitectura §7). */
    private static void assertContentMatchesDeclaredType(String dataUri) {
        int comma = dataUri.indexOf(',');
        String header = comma < 0 ? "" : dataUri.substring(5, comma).toLowerCase(Locale.ROOT);
        if (comma < 0 || !header.endsWith(";base64")) {
            throw new DomainException("El documento debe ir como data URI base64.");
        }
        String mime = header.substring(0, header.length() - ";base64".length());
        // 16 caracteres de base64 son 12 bytes: suficientes para cualquier firma reconocida.
        String head = dataUri.substring(comma + 1, Math.min(dataUri.length(), comma + 1 + 16));
        byte[] start;
        try {
            start = Base64.getDecoder().decode(head.substring(0, head.length() - head.length() % 4));
        } catch (IllegalArgumentException e) {
            throw new DomainException("El documento no es base64 valido.");
        }
        if (List.of("application/pdf", "image/png", "image/jpeg").contains(mime) && !FileSignature.matches(mime, start)) {
            throw new DomainException("El contenido del documento no es un " + mime + ".");
        }
    }

    /** Un array vacio se rechaza: o no va el campo, o van uno o dos documentos. */
    public static void assertValid(List<SupportingDocument> documents, NatureOfPayment nature,
                                   boolean cryptoFunded) {
        boolean required = cryptoFunded && (nature == null || nature.requiresSupportingDocuments());

        if (documents == null || documents.isEmpty()) {
            if (required) {
                throw new DomainException("Un pago fondeado con cripto necesita al menos un documento "
                        + "de soporte.");
            }
            return;
        }
        if (documents.size() > MAX_DOCUMENTS) {
            throw new DomainException("Se admiten como maximo " + MAX_DOCUMENTS + " documentos de soporte.");
        }
    }
}
