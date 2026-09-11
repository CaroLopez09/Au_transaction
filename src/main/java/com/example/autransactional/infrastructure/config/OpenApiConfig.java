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

                                Todo lo de negocio vive en KiraFin: el BFF custodia las credenciales,
                                aplica la sesion y los roles de cada empresa, y guarda lo que la API
                                de Kira no devuelve o no ofrece (maker-checker, cuestionario KYB,
                                bitacora de webhooks).

                                Grupos de endpoints:
                                - /api/auth            sesion propia del BFF (login y perfil)
                                - /api/onboarding, /api/ubos, /api/rfis   KYB y cumplimiento
                                - /api/virtual-accounts, /api/deposits    cuentas y fondeo
                                - /api/recipients, /api/quotations, /api/payouts   tesoreria
                                - /api/reference       catalogos de Kira
                                - /api/webhooks/kira   ingress de eventos de Kira, firmado con HMAC

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
