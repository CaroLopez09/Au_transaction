package com.example.autransactional.infrastructure.identity;

import com.example.autransactional.domain.compliance.identity.DocumentData;
import com.example.autransactional.domain.compliance.identity.DocumentOcr;

/** OCR de desarrollo: devuelve datos fijos para que el contrato de respuesta sea completo. */
public class DevDocumentOcr implements DocumentOcr {

    @Override
    public DocumentData read(byte[] front, byte[] back, String countryCode, String documentType) {
        if (front == null || front.length == 0) {
            return null;
        }
        return new DocumentData(
                "1020304050",
                "MARIA",
                "GONZALEZ",
                "1990-05-15",
                "2030-05-15",
                countryCode,
                countryCode,
                "F");
    }
}
