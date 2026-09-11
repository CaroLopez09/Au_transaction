package com.example.autransactional.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Autentica la peticion con el JWT propio del BFF y fija la organizacion en el contexto del hilo.
 * Un token invalido no autentica y no fija tenant: la peticion sigue como anonima y es
 * la cadena de autorizacion la que decide si el recurso exige sesion.
 */
@Component
public class JwtTenantFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtTenantFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // El webhook de Kira no trae JWT: se autentica por firma HMAC en su propio controlador.
        return request.getRequestURI().startsWith("/api/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith(BEARER)) {
            try {
                AuthenticatedOperator operator = jwtService.verify(authHeader.substring(BEARER.length()));

                TenantContext.set(operator.tenantId());

                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + operator.role().name()));
                var authentication = new UsernamePasswordAuthenticationToken(operator, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (Exception e) {
                SecurityContextHolder.clearContext();
                TenantContext.clear();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"code\":\"unauthorized\",\"message\":\"Token invalido o expirado.\"}");
                return;
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
