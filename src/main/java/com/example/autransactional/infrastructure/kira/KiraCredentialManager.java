package com.example.autransactional.infrastructure.kira;

import tools.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Obtiene y cachea el bearer token de Kira.
 *
 * POST /auth es el unico endpoint que se autentica solo con x-api-key; el cuerpo lleva
 * client_id y password. El token vive 3600s y no hay refresh token: se vuelve a autenticar.
 * Cacheamos con margen para no llamar en cada peticion, e invalidamos ante cualquier 401.
 */
@Service
public class KiraCredentialManager {

    public static final String CACHE_KEY = "kira-access-token";

    private static final Logger log = LoggerFactory.getLogger(KiraCredentialManager.class);

    private final RestClient restClient;
    private final KiraProperties properties;
    private final KiraErrorParser errorParser;
    private final Cache<String, String> tokenCache;
    private final ObjectMapper objectMapper;

    public KiraCredentialManager(RestClient kiraRestClient,
                                 KiraProperties properties,
                                 KiraErrorParser errorParser,
                                 Cache<String, String> kiraTokenCache,
                                 ObjectMapper objectMapper) {
        this.restClient = kiraRestClient;
        this.properties = properties;
        this.errorParser = errorParser;
        this.tokenCache = kiraTokenCache;
        this.objectMapper = objectMapper;
    }

    private static void requireCredential(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new KiraNotConfiguredException("Falta " + variable
                    + ". Kira entrega api_key, client_id y password por canal seguro.");
        }
    }

    public String getAccessToken() {
        return tokenCache.get(CACHE_KEY, key -> authenticate());
    }

    /** Se invoca cuando una llamada responde 401: el siguiente getAccessToken reautentica. */
    public void invalidate() {
        tokenCache.invalidate(CACHE_KEY);
    }

    private String authenticate() {
        // Las tres son obligatorias: sin client_id o password el cuerpo ni siquiera se construye.
        requireCredential(properties.apiKey(), "KIRA_API_KEY");
        requireCredential(properties.clientId(), "KIRA_CLIENT_ID");
        requireCredential(properties.password(), "KIRA_PASSWORD");
        log.info("Solicitando nuevo access token a KiraFin");

        var response = restClient.post()
                .uri("/auth")
                .header("x-api-key", properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("client_id", properties.clientId(), "password", properties.password()))
                .exchange((request, clientResponse) -> {
                    String body = new String(clientResponse.getBody().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (clientResponse.getStatusCode().isError()) {
                        throw errorParser.parse(clientResponse.getStatusCode().value(), body);
                    }
                    return body;
                });

        KiraAuthResponse parsed = readAuth(response);
        if (parsed == null || parsed.data() == null || parsed.data().accessToken() == null) {
            throw new IllegalStateException("KiraFin no devolvio access_token en POST /auth.");
        }
        return parsed.data().accessToken();
    }

    private KiraAuthResponse readAuth(String body) {
        try {
            return objectMapper.readValue(body, KiraAuthResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Respuesta de POST /auth ilegible: " + e.getMessage(), e);
        }
    }
}
