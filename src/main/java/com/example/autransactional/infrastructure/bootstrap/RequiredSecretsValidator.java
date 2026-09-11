package com.example.autransactional.infrastructure.bootstrap;

import com.example.autransactional.infrastructure.kira.KiraProperties;
import com.example.autransactional.infrastructure.security.BffSecurityProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * En cert y prod el arranque falla si falta un secreto, en vez de descubrirlo con la primera
 * llamada a Kira o con un token firmado con una clave de ejemplo.
 * En dev no se aplica: alli hay valores por defecto deliberados.
 */
@Component
@Profile({"cert", "prod"})
public class RequiredSecretsValidator implements InitializingBean {

    private final KiraProperties kira;
    private final BffSecurityProperties security;

    public RequiredSecretsValidator(KiraProperties kira, BffSecurityProperties security) {
        this.kira = kira;
        this.security = security;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> missing = new ArrayList<>();

        require(missing, kira.apiKey(), "KIRA_API_KEY");
        require(missing, kira.clientId(), "KIRA_CLIENT_ID");
        require(missing, kira.password(), "KIRA_PASSWORD");
        require(missing, kira.webhookSecret(), "KIRA_WEBHOOK_SECRET");
        require(missing, security.jwtSecret(), "BFF_JWT_SECRET");

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Faltan variables de entorno obligatorias fuera de desarrollo: " + String.join(", ", missing));
        }

        // HMAC256 sobre una clave corta es debil aunque el algoritmo la acepte.
        if (security.jwtSecret().length() < 32) {
            throw new IllegalStateException("BFF_JWT_SECRET debe tener al menos 32 caracteres.");
        }
    }

    private static void require(List<String> missing, String value, String name) {
        if (value == null || value.isBlank()) {
            missing.add(name);
        }
    }
}
