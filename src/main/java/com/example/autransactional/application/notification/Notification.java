package com.example.autransactional.application.notification;

import com.example.autransactional.domain.shared.TenantId;

import java.time.Instant;

/**
 * Aviso de negocio para las personas de una organizacion (arquitectura §2.6).
 *
 * Nace de un evento de Kira ya proyectado: el aviso dice que algo cambio, pero lo que manda es
 * el recurso, que la pantalla vuelve a leer. No lleva datos personales ni importes completos.
 */
public record Notification(
        String id,
        TenantId tenantId,
        String kind,
        /** info, success, attention o critical: el tono con que se pinta. */
        String severity,
        String title,
        String message,
        String resourceType,
        String resourceId,
        Instant createdAt) {
}
