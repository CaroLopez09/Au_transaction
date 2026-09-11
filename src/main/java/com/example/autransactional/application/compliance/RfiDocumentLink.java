package com.example.autransactional.application.compliance;

import java.time.Instant;

/**
 * Enlace temporal de descarga de un archivo de un RFI.
 *
 * La URL es una credencial al portador que caduca en minutos: el portal la abre al momento y
 * pide otra si caduca. No se guarda ni se registra en ningun log.
 */
public record RfiDocumentLink(String downloadUrl, Instant expiresAt) {
}
