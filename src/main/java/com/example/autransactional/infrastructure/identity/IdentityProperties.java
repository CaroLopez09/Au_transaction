package com.example.autransactional.infrastructure.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Politica de verificacion. Vive en el servidor a proposito: los umbrales son una decision
 * de riesgo, no una preferencia de la interfaz.
 */
@ConfigurationProperties(prefix = "bff.identity")
public record IdentityProperties(
        /* dev | aws. Selecciona los adaptadores de los cuatro puertos biometricos. */
        @DefaultValue("dev") String provider,
        @DefaultValue("true") boolean livenessEnabled,
        @DefaultValue("80") int minLivenessScore,
        @DefaultValue("85") int minMatchScore,
        @DefaultValue("70") int reviewFloor,
        @DefaultValue("120") long challengeTtlSeconds,
        @DefaultValue("1800") long sessionTtlSeconds,
        @DefaultValue("10485760") long maxUploadBytes,
        /* Solo para el adaptador de desarrollo: permite recorrer el camino de rechazo. */
        @DefaultValue("true") boolean devLivenessPasses,
        Aws aws) {

    /** Datos que el navegador necesita para hablar con AWS. Nunca credenciales. */
    public record Aws(String region, String identityPoolId) {
    }
}
