package com.example.autransactional.application.treasury;

/**
 * Destinatario tal como lo tiene registrado Kira.
 *
 * Sirve para conciliar el directorio local con Kira: localRecipientId es nulo si el destinatario
 * existe en Kira pero no se dio de alta desde este portal. La cuenta va enmascarada, igual que
 * en el directorio local.
 */
public record KiraRecipientView(
        String kiraRecipientId,
        String localRecipientId,
        String type,
        String name,
        String accountType,
        String maskedDestination,
        String email,
        String createdAt) {
}
