package com.example.autransactional.domain.compliance.identity;

/**
 * Resultado de la prueba de vida.
 *
 * referenceImageBase64 es el fotograma que el proveedor capturo DURANTE la prueba, y es el
 * que se compara contra el documento. No se toma una selfie aparte: eso garantiza que la
 * cara comparada es la misma que supero la prueba, y no una foto tomada despues.
 */
public record LivenessOutcome(String sessionId, String status, double confidence,
                              boolean passed, String referenceImageBase64) {
}
