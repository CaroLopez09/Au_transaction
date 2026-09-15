package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que Kira acepta dentro de identifying_information[].
 *
 * Las reglas de aqui son las que impiden un 400 o, peor, un 200 con el documento perdido:
 * el data URI bien formado, el tope de 10 MB del cuerpo y el array completo en cada PUT.
 */
class KybDocumentsTest {

    private static KybDocumentCommands.DocumentFile archivo(String tipo, String mime, int bytes) {
        return new KybDocumentCommands.DocumentFile(tipo,
                new KybDocumentCommands.UploadedFile("doc.pdf", mime, new byte[bytes]));
    }

    private static KybDocumentCommands.AttachDocuments comando(
            List<KybDocumentCommands.DocumentFile> archivos) {
        return new KybDocumentCommands.AttachDocuments(
                "business_formation", "col", "900123456", null, archivos);
    }

    @Test
    void elArchivoViajaComoDataUriEnBase64() {
        var file = new KybDocumentCommands.UploadedFile("acta.pdf", "application/pdf",
                "PDF".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> entry = KybDocuments.toIdentifyingInformation(comando(
                List.of(new KybDocumentCommands.DocumentFile("file_business_formation", file))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) entry.get("documents");
        // "UERG" es "PDF" en base64.
        assertEquals("data:application/pdf;base64,UERG", documents.get(0).get("file"));
        assertEquals("file_business_formation", documents.get(0).get("type"));
        assertEquals("acta.pdf", documents.get(0).get("file_name"));
    }

    @Test
    void elPaisEmisorViajaEnIso3Mayusculas() {
        Map<String, Object> entry = KybDocuments.toIdentifyingInformation(
                comando(List.of(archivo("front", "image/png", 10))));

        // Kira exige alpha-3; en minusculas responde 400.
        assertEquals("COL", entry.get("issuing_country"));
        assertEquals("business_formation", entry.get("type"));
        assertEquals("900123456", entry.get("number"));
    }

    @Test
    void rechazaUnTipoDeDocumentoQueKiraNoConoce() {
        DomainException e = assertThrows(DomainException.class, () ->
                KybDocuments.toIdentifyingInformation(comando(List.of(archivo("anverso", "image/png", 10)))));
        assertTrue(e.getMessage().contains("anverso"));
    }

    @Test
    void rechazaUnMimeQueNoViajaEnBase64() {
        // Kira admite HEIC y WebP en los RFI, pero en un data URI solo JPEG, PNG y PDF.
        DomainException e = assertThrows(DomainException.class, () ->
                KybDocuments.toIdentifyingInformation(comando(List.of(archivo("front", "image/heic", 10)))));
        assertTrue(e.getMessage().contains("image/heic"));
    }

    @Test
    void rechazaUnArchivoVacio() {
        assertThrows(DomainException.class, () ->
                KybDocuments.toIdentifyingInformation(comando(List.of(archivo("front", "image/png", 0)))));
    }

    @Test
    void rechazaElLoteQueReventariaElCuerpoDe10Mb() {
        // 2 x 4 MB crudos son ~10,7 MB ya en base64: Kira devolveria 413 y el portal
        // no sabria decir cual de los dos archivos sobra.
        List<KybDocumentCommands.DocumentFile> grandes = List.of(
                archivo("front", "image/png", 4 * 1024 * 1024),
                archivo("back", "image/png", 4 * 1024 * 1024));

        DomainException e = assertThrows(DomainException.class,
                () -> KybDocuments.toIdentifyingInformation(comando(grandes)));
        assertTrue(e.getMessage().contains("7 MB"));
    }

    @Test
    void rechazaMasDeDiezArchivos() {
        List<KybDocumentCommands.DocumentFile> muchos = new ArrayList<>();
        for (int i = 0; i <= KybDocuments.MAX_FILES; i++) {
            muchos.add(archivo("front", "image/png", 10));
        }
        assertThrows(DomainException.class, () -> KybDocuments.toIdentifyingInformation(comando(muchos)));
    }

    @Test
    void laFusionReemplazaElRegistroDelMismoTipoYConservaLosDemas() {
        List<Map<String, Object>> guardado = List.of(
                new LinkedHashMap<>(Map.of("type", "ein", "number", "12-3456789")),
                new LinkedHashMap<>(Map.of("type", "business_formation", "number", "viejo")));

        Map<String, Object> nuevo = new LinkedHashMap<>(Map.of("type", "business_formation", "number", "nuevo"));
        List<Map<String, Object>> merged = KybDocuments.merge(guardado, nuevo);

        // El array viaja completo: si se mandara solo el nuevo, el ein se perderia.
        assertEquals(2, merged.size());
        assertEquals("ein", merged.get(0).get("type"));
        assertEquals("nuevo", merged.get(1).get("number"));
    }

    @Test
    void loQueSePersisteNoLlevaLosArchivos() {
        Map<String, Object> entry = KybDocuments.toIdentifyingInformation(
                comando(List.of(archivo("front", "image/png", 10))));

        List<Map<String, Object>> limpio = KybDocuments.withoutFiles(List.of(entry));

        // Guardar el base64 significaria reenviarlo en cada PUT hasta reventar los 10 MB.
        assertFalse(limpio.get(0).containsKey("documents"));
        assertEquals("business_formation", limpio.get(0).get("type"));
        // La entrada original no se toca: es la que viaja a Kira en esta misma llamada.
        assertTrue(entry.containsKey("documents"));
    }
}
