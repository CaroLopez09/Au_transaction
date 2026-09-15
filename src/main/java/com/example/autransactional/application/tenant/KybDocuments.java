package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.FileSignature;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Construye la entrada de identifying_information[] que Kira espera dentro del PUT /v1/users.
 *
 * Kira admite dos formas de mandar un archivo: un data URI en base64, o una URL https que
 * descarga despues. Aqui se usa base64 porque la URL exige un dominio preautorizado por Kira
 * y un host publico. El precio es el tope de 10 MB del cuerpo entero, y base64 anade un tercio
 * sobre el tamano real del archivo: por eso el limite se controla sobre los bytes crudos.
 *
 * Los documentos NUNCA se guardan en el BFF: Kira los custodia. Por eso
 * {@link #withoutFiles(List)} limpia los archivos antes de persistir el payload de onboarding.
 * Es seguro reenviar la entrada sin ellos, porque en un PUT "a missing file works differently:
 * sending other fields will not clear it".
 */
public final class KybDocuments {

    /** Formas de archivo que Kira acepta en un data URI. */
    static final List<String> MIME_TYPES = List.of("image/jpeg", "image/png", "application/pdf");

    /** Roles del archivo dentro de su registro. */
    static final Set<String> DOCUMENT_TYPES = Set.of(
            "front", "back", "selfie",
            "file_proof_of_address", "file_business_formation", "file_source_of_wealth",
            "file_ein_letter", "file_bylaws", "file_corporate_resolution",
            "file_certificate_of_registration", "file_certificate_of_good_standing",
            "file_board_minutes", "file_portfolio_statement", "file_fatca",
            "file_company_fiscal_registration");

    static final int MAX_FILES = 10;

    /**
     * Tope por peticion sobre los bytes crudos. El cuerpo entero no puede pasar de 10 MB y
     * el base64 lo infla ~4/3, asi que 7 MB de archivos son ~9,4 MB de cuerpo.
     */
    static final long MAX_TOTAL_BYTES = 7L * 1024 * 1024;

    private KybDocuments() {
    }

    /**
     * Valida y construye la entrada. Todo o nada: si un archivo no sirve no se manda ninguno,
     * porque un PUT a medias deja el expediente en un estado que el portal no sabe describir.
     */
    static Map<String, Object> toIdentifyingInformation(KybDocumentCommands.AttachDocuments command) {
        List<KybDocumentCommands.DocumentFile> files = command.documents();
        if (files == null || files.isEmpty()) {
            throw new DomainException("Adjunta al menos un archivo.");
        }
        if (files.size() > MAX_FILES) {
            throw new DomainException("Como maximo " + MAX_FILES + " archivos por peticion.");
        }

        long total = 0;
        List<Map<String, Object>> documents = new java.util.ArrayList<>();
        for (KybDocumentCommands.DocumentFile document : files) {
            total += validate(document);
            documents.add(toDocument(document));
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new DomainException("El conjunto supera los 7 MB. Kira limita la peticion entera "
                    + "a 10 MB y el base64 anade un tercio: sube los archivos en varias tandas.");
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", command.informationType().trim().toLowerCase(Locale.ROOT));
        entry.put("issuing_country", command.issuingCountry().trim().toUpperCase(Locale.ROOT));
        if (command.number() != null && !command.number().isBlank()) {
            entry.put("number", command.number().trim());
        }
        if (command.expiration() != null && !command.expiration().isBlank()) {
            entry.put("expiration", command.expiration().trim());
        }
        entry.put("documents", documents);
        return entry;
    }

    /** Devuelve el tamano en bytes crudos del archivo validado. */
    private static long validate(KybDocumentCommands.DocumentFile document) {
        String role = document.documentType() == null ? "" : document.documentType().trim().toLowerCase(Locale.ROOT);
        if (!DOCUMENT_TYPES.contains(role)) {
            throw new DomainException("Tipo de documento no admitido: '" + role
                    + "'. Admitidos: front, back, selfie y los file_*.");
        }
        KybDocumentCommands.UploadedFile file = document.file();
        String name = file == null || file.fileName() == null ? "archivo" : file.fileName();
        if (file == null || file.content() == null || file.content().length == 0) {
            throw new DomainException("El archivo '" + name + "' esta vacio.");
        }
        String mime = file.contentType() == null ? "" : file.contentType().trim().toLowerCase(Locale.ROOT);
        if (!MIME_TYPES.contains(mime)) {
            throw new DomainException("Tipo no admitido en '" + name + "' (" + mime + "). "
                    + "Kira acepta en base64: " + String.join(", ", MIME_TYPES) + ".");
        }
        if (!FileSignature.matches(mime, file.content())) {
            throw new DomainException("El contenido de '" + name + "' no es un " + mime
                    + ": el archivo esta danado o tiene otra extension.");
        }
        return file.content().length;
    }

    private static Map<String, Object> toDocument(KybDocumentCommands.DocumentFile document) {
        KybDocumentCommands.UploadedFile file = document.file();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", document.documentType().trim().toLowerCase(Locale.ROOT));
        entry.put("file", "data:" + file.contentType().trim().toLowerCase(Locale.ROOT)
                + ";base64," + Base64.getEncoder().encodeToString(file.content()));
        if (file.fileName() != null && !file.fileName().isBlank()) {
            entry.put("file_name", file.fileName());
        }
        return entry;
    }

    /**
     * Fusiona la entrada nueva con las ya enviadas: Kira reemplaza la que coincide en `type`
     * y deja las demas, asi que el array tiene que viajar completo o se pierden las otras.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> merge(Object stored, Map<String, Object> incoming) {
        List<Map<String, Object>> merged = new java.util.ArrayList<>();
        if (stored instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && !sameType(map, incoming)) {
                    merged.add((Map<String, Object>) map);
                }
            }
        }
        merged.add(incoming);
        return merged;
    }

    private static boolean sameType(Map<?, ?> stored, Map<String, Object> incoming) {
        Object type = stored.get("type");
        return type != null && type.equals(incoming.get("type"));
    }

    /**
     * Copia las entradas sin los archivos, para persistir el payload sin base64 dentro.
     * Guardarlos significaria reenviarlos en cada PUT posterior hasta reventar el tope de 10 MB.
     */
    static List<Map<String, Object>> withoutFiles(List<Map<String, Object>> entries) {
        List<Map<String, Object>> clean = new java.util.ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Map<String, Object> copy = new LinkedHashMap<>(entry);
            copy.remove("documents");
            clean.add(copy);
        }
        return clean;
    }
}
