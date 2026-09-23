package com.example.autransactional.infrastructure.email;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class EmailClientConfig {

    /**
     * baseUrl puede llegar vacio (entorno sin proveedor de correo configurado): el bean se arma
     * igual para que el contexto levante, y {@link EmailProperties#configured()} es quien decide
     * si se usa antes de cualquier llamada real.
     */
    @Bean
    public RestClient emailRestClient(EmailProperties properties) {
        return RestClient.builder().baseUrl(properties.baseUrl() == null ? "" : properties.baseUrl()).build();
    }
}
