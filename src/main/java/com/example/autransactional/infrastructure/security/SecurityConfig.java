package com.example.autransactional.infrastructure.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(BffSecurityProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtTenantFilter jwtTenantFilter) throws Exception {
        http
                // API sin estado: no hay sesion de servlet que proteger con CSRF.
                // El webhook se autentica por HMAC, no por cookie.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/webhooks/**").permitAll()
                        // Verificacion biometrica: la ejecuta la persona que se vincula,
                        // que todavia no tiene sesion. El aislamiento por organizacion lo
                        // da el clientId y la sesion de verificacion del servidor.
                        .requestMatchers("/api/v1/liveness/**").permitAll()
                        .requestMatchers("/api/v1/number-challenge/**").permitAll()
                        .requestMatchers("/api/v1/identity/validate").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Swagger UI. Se apaga por configuracion en prod, no por esta regla.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtTenantFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
