package com.example.autransactional.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentacion viva de la API del BFF.
 *
 * El esquema 'bearer-jwt' es el JWT propio del BFF, no el de Kira: el token de Kira nunca
 * sale del servidor. Los endpoints de verificacion biometrica no llevan seguridad porque
 * los invoca la persona que se esta vinculando, que todavia no tiene sesion.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bffOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AuTransactional BFF")
                        .version("0.0.1-SNAPSHOT")
                        .description("""
                                Backend for Frontend de la integracion con KiraFin.

                                Grupos de endpoints:
                                - /api/auth          sesion propia del BFF (login y perfil)
                                - /api/payouts       pagos con control interno maker-checker
                                - /api/v1/identity   verificacion biometrica: documento, rostro y voz
                                - /api/v1/liveness   prueba de vida
                                - /api/v1/number-challenge  reto de voz de 4 digitos
                                - /api/webhooks/kira ingress de eventos de Kira, firmado con HMAC

                                Para probar los endpoints protegidos: POST /api/auth/login,
                                copia accessToken y pulsa Authorize.
                                """))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT emitido por POST /api/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
