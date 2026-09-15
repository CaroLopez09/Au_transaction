package com.example.autransactional.application.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class KybDocumentCommands {

    private KybDocumentCommands() {
    }

    /** Archivo recibido del portal. Mismo contrato que el de los RFI. */
    public record UploadedFile(String fileName, String contentType, byte[] content) {
    }

    /** Un archivo con el papel que cumple dentro del registro: front, back, selfie o file_*. */
    public record DocumentFile(String documentType, UploadedFile file) {
    }

    /**
     * Una entrada de identifying_information[] con sus archivos.
     *
     * Kira no tiene endpoint de subida: los documentos viajan dentro del PUT /v1/users,
     * anidados en el registro al que pertenecen. Por eso una peticion cubre un registro
     * (el pasaporte, o el acta de constitucion) y todos sus archivos a la vez.
     */
    public record AttachDocuments(
            @NotBlank String informationType,
            @NotBlank @Size(min = 3, max = 3) String issuingCountry,
            String number,
            String expiration,
            List<DocumentFile> documents) {
    }
}
