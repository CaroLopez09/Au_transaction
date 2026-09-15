package com.example.autransactional.application.compliance;

import java.time.Instant;

/**
 * Enlace de verificacion de identidad de un beneficiario pedido por un RFI (item ubo_link).
 *
 * Caduca en torno a una hora y es de un solo uso para esa persona: se acuna cuando la persona
 * pulsa, no se guarda ni se registra en ningun log.
 */
public record RfiUboLink(String url, Instant expiresAt) {
}
