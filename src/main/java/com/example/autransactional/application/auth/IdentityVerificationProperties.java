package com.example.autransactional.application.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Interruptor para el reto biometrico en el login.
 *
 * En cert y prod siempre debe estar en true. Se deja apagable solo para desarrollo local,
 * donde BIOMETRY_BASE_URL no esta configurado y por tanto el reto nunca se puede completar.
 */
@ConfigurationProperties(prefix = "bff.identity-verification")
public record IdentityVerificationProperties(@DefaultValue("true") boolean enabled) {
}
