package com.example.autransactional.domain.compliance.identity;

/**
 * Veredicto de la validacion. Lo emite el backend y solo el backend: la recomendacion S-1
 * del documento de integracion senala que hoy el frontend puede aprobar por su cuenta si
 * los scores superan umbrales que el propio navegador configura. Aqui no.
 */
public enum VerificationVerdict {
    PENDING,
    APPROVED,
    MANUAL_REVIEW,
    REJECTED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
