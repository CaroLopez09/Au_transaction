package com.example.autransactional.domain.treasury;

/**
 * Kira no expone actualizacion ni borrado de destinatarios. Para corregir uno se crea
 * un reemplazo y el anterior se archiva: ARCHIVED es un estado puramente local.
 */
public enum RecipientStatus {
    ACTIVE,
    ARCHIVED
}
