package com.example.autransactional.infrastructure.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Conexion privada del BFF al servicio corporativo de correo/OTP (mismo proveedor que la
 * verificacion biometrica, host {@code pruebas.bankvision.com}). Ninguna credencial viaja al
 * navegador: el BFF es el unico que llama a este servicio.
 */
@ConfigurationProperties(prefix = "email")
public record EmailProperties(String baseUrl, String apiKey, String fromName) {

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
