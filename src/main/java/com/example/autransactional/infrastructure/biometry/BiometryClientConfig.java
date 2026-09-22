package com.example.autransactional.infrastructure.biometry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class BiometryClientConfig {

    /**
     * baseUrl puede llegar vacio (entorno sin proveedor biometrico configurado): el bean se
     * arma igual para que el contexto levante, y {@link BiometryProperties#configured()} es
     * quien decide si se usa antes de cualquier llamada real.
     */
    @Bean
    public RestClient biometryRestClient(BiometryProperties properties) {
        return RestClient.builder().baseUrl(properties.baseUrl() == null ? "" : properties.baseUrl()).build();
    }
}
