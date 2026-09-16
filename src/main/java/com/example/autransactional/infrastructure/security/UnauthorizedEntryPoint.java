package com.example.autransactional.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Respuesta a una peticion sin sesion (F7).
 *
 * Sin esto, Spring Security devolvia un 403 con cuerpo vacio cuando faltaba la cabecera
 * Authorization, y el portal tenia que adivinar por el hueco: un 403 vacio significaba
 * "no hay sesion" y un 403 con cuerpo, "rol sin permiso". Ahora la falta de credencial es
 * 401 con el mismo codigo `unauthorized` que ya emite JwtTenantFilter para un token invalido,
 * y el 403 queda solo para lo que de verdad es falta de permiso.
 */
@Component
public class UnauthorizedEntryPoint implements AuthenticationEntryPoint {

    static final String BODY = """
            {"code":"unauthorized","message":"Token de autorizacion ausente o invalido."}""";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(BODY);
    }
}
