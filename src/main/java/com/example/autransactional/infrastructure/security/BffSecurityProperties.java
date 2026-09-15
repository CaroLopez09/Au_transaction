package com.example.autransactional.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bff.security")
public record BffSecurityProperties(
        String jwtSecret,
        @DefaultValue("autransactional-bff") String jwtIssuer,
        @DefaultValue("28800000") long tokenExpirationMs,
        /* Clave para cifrar los secretos TOTP en reposo. Obligatoria en cert y prod. */
        String mfaEncryptionKey,
        /* Si es true, nadie entra sin segundo factor: quien no lo tiene lo configura al iniciar sesion. */
        @DefaultValue("false") boolean mfaEnforced,
        /* Vigencia del reto entre la contrasena y el codigo. */
        @DefaultValue("300000") long mfaChallengeTtlMs,
        /* Nombre que muestra la app autenticadora. */
        @DefaultValue("AU Transactional") String mfaIssuer) {
}
