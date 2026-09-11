package com.example.autransactional.infrastructure.kira;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(KiraProperties.class)
public class KiraClientConfig {

    @Bean
    public RestClient kiraRestClient(KiraProperties properties) {
        // JdkClientHttpRequestFactory (java.net.http) y no el Simple*: este ultimo va sobre
        // HttpURLConnection, que no admite PATCH, y PATCH /v1/rfis/{id}/items lo necesita.
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Cache del bearer token de Kira. Vive 3600s; expiramos antes por el margen configurado
     * para no usar nunca un token a punto de vencer.
     */
    @Bean
    public Cache<String, String> kiraTokenCache(KiraProperties properties) {
        long ttl = Math.max(60, properties.tokenTtlSeconds() - properties.tokenRefreshMarginSeconds());
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttl))
                .maximumSize(1)
                .build();
    }
}
