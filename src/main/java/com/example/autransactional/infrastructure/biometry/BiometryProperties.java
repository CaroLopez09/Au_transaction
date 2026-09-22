package com.example.autransactional.infrastructure.biometry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Conexion privada del BFF al servicio que decide la identidad biometrica. */
@ConfigurationProperties(prefix = "biometry")
public record BiometryProperties(String baseUrl, String apiKey) {

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}