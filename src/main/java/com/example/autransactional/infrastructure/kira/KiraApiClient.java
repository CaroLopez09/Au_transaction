package com.example.autransactional.infrastructure.kira;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.example.autransactional.domain.shared.IdempotencyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptador HTTP unico hacia KiraFin.
 *
 * Responsabilidades que la documentacion exige y que ningun caso de uso debe repetir:
 *  - x-api-key en TODA peticion, incluida POST /auth.
 *  - Authorization: Bearer en toda peticion salvo POST /auth.
 *  - X-Api-Version en cada peticion mientras la cuenta no este fijada. Los RFIs la
 *    sobrescriben: solo existen en 2026-06-01, y la cabecera por peticion gana al pin.
 *  - Idempotency-Key (UUID v4) en POST /v1/users, /v1/recipients, /v1/virtual-accounts
 *    y /v1/virtual-accounts/{id}/payout.
 *  - Reautenticar y reintentar una vez ante un 401.
 *  - Normalizar las varias formas de error que conviven hoy en la API.
 */
@Component
public class KiraApiClient {

    private static final Logger log = LoggerFactory.getLogger(KiraApiClient.class);

    /** Las seis rutas de RFI solo existen en esta version; con 2026-04-14 no se encuentran. */
    static final String RFI_API_VERSION = "2026-06-01";

    /**
     * La cotizacion desglosada (fees[], totals, pricing_context) solo existe desde esta version;
     * con 2026-04-14 la respuesta es una forma simple sin totals, y de ahi salen las comisiones
     * reales que hereda el pago.
     */
    static final String QUOTATION_API_VERSION = "2026-06-01";

    private final RestClient restClient;
    private final KiraCredentialManager credentialManager;
    private final KiraProperties properties;
    private final KiraErrorParser errorParser;
    private final ObjectMapper objectMapper;

    public KiraApiClient(RestClient kiraRestClient,
                         KiraCredentialManager credentialManager,
                         KiraProperties properties,
                         KiraErrorParser errorParser,
                         ObjectMapper objectMapper) {
        this.restClient = kiraRestClient;
        this.credentialManager = credentialManager;
        this.properties = properties;
        this.errorParser = errorParser;
        this.objectMapper = objectMapper;
    }

    // ---------- Operaciones de negocio ----------

    public JsonNode getUser(String userId) {
        return exchange(HttpMethod.GET, "/v1/users/" + userId, null, null);
    }

    public JsonNode createUser(Object body, IdempotencyKey key) {
        return exchange(HttpMethod.POST, "/v1/users", body, key);
    }

    /** PUT, no PATCH: PATCH no esta soportado en esta ruta. Solo se escriben los campos enviados. */
    public JsonNode updateUser(String userId, Object body) {
        return exchange(HttpMethod.PUT, "/v1/users/" + userId, body, null);
    }

    /**
     * Un enlace por cada beneficiario final, con vigencia de 7 dias. Llamadas repetidas
     * devuelven el mismo enlace salvo que cambie 'redirect', cuyas URLs deben estar
     * preautorizadas por Kira. Da 422 si la verificacion aun no se ha disparado.
     */
    public JsonNode requestLivenessLink(String userId, Object body) {
        return exchange(HttpMethod.POST, "/v1/users/" + userId + "/liveness-link",
                body == null ? Map.of() : body, null);
    }

    public JsonNode listVirtualAccounts(Map<String, ?> query) {
        return exchange(HttpMethod.GET, withQuery("/v1/virtual-accounts", query), null, null);
    }

    public JsonNode getVirtualAccount(String virtualAccountId) {
        return exchange(HttpMethod.GET, "/v1/virtual-accounts/" + virtualAccountId, null, null);
    }

    public JsonNode createVirtualAccount(Object body, IdempotencyKey key) {
        return exchange(HttpMethod.POST, "/v1/virtual-accounts", body, key);
    }

    /** Devuelve 400 mientras la cuenta sigue activandose; 200 con available_balance si esta activa. */
    public JsonNode getVirtualAccountBalance(String virtualAccountId) {
        return exchange(HttpMethod.GET, "/v1/virtual-accounts/" + virtualAccountId + "/balance", null, null);
    }

    /**
     * Solo sandbox: acredita saldo de inmediato. En produccion responde 403.
     * El monto 11 es un valor magico que devuelve estado 'refunded', para probar esa rama.
     */
    public JsonNode simulateDeposit(String virtualAccountId, Object body) {
        return exchange(HttpMethod.POST,
                "/v1/virtual-accounts/" + virtualAccountId + "/simulate-deposit", body, null);
    }

    /** Array desnudo, paginado con limit (1-100) + offset. */
    public JsonNode listAccountDeposits(String virtualAccountId, Map<String, ?> query) {
        return exchange(HttpMethod.GET,
                withQuery("/v1/virtual-accounts/" + virtualAccountId + "/deposits", query), null, null);
    }

    /** user_id es obligatorio: omitirlo devuelve 400. */
    public JsonNode listRecipients(String userId, Map<String, ?> extraQuery) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("user_id", userId);
        if (extraQuery != null) {
            query.putAll(extraQuery);
        }
        return exchange(HttpMethod.GET, withQuery("/v1/recipients", query), null, null);
    }

    public JsonNode getRecipient(String recipientId) {
        return exchange(HttpMethod.GET, "/v1/recipients/" + recipientId, null, null);
    }

    /**
     * Devuelve el codigo de estado porque un 202 significa "ya existia": es un exito y hay
     * que poder distinguirlo del 201 de alta nueva.
     */
    public KiraResponse createRecipient(Object body, IdempotencyKey key) {
        return exchangeWithStatus(HttpMethod.POST, "/v1/recipients", body, key);
    }

    public JsonNode createQuotation(Object body) {
        return exchangeWithStatus(HttpMethod.POST, "/v1/quotations", body, null,
                QUOTATION_API_VERSION).body();
    }

    public JsonNode previewPayout(String virtualAccountId, Object body) {
        return exchange(HttpMethod.POST,
                "/v1/virtual-accounts/" + virtualAccountId + "/payout/preview", body, null);
    }

    /** El 201 responde status "created" en minusculas y el identificador en el campo id. */
    public JsonNode executePayout(String virtualAccountId, Object body, IdempotencyKey key) {
        return exchange(HttpMethod.POST, "/v1/virtual-accounts/" + virtualAccountId + "/payout", body, key);
    }

    /** El GET responde status en MAYUSCULAS y el identificador en payout_id. */
    public JsonNode getPayout(String payoutId) {
        return exchange(HttpMethod.GET, "/v1/payouts/" + payoutId, null, null);
    }

    public JsonNode listPayouts(Map<String, ?> query) {
        return exchange(HttpMethod.GET, withQuery("/v1/payouts", query), null, null);
    }

    /** Pagina con limit + offset (tope 100), no con page como pagos y depositos. */
    public JsonNode listRfis(Map<String, ?> query) {
        return exchangeWithStatus(HttpMethod.GET, withQuery("/v1/rfis", query), null, null,
                RFI_API_VERSION).body();
    }

    public JsonNode getRfi(String rfiId) {
        return exchangeWithStatus(HttpMethod.GET, "/v1/rfis/" + rfiId, null, null, RFI_API_VERSION).body();
    }

    /** All-or-nothing: si un item no cumple su answer_spec, 422 y no se guarda ninguno. 409 si esta cerrado. */
    public JsonNode answerRfiItems(String rfiId, Object body) {
        return exchangeWithStatus(HttpMethod.PATCH, "/v1/rfis/" + rfiId + "/items", body, null,
                RFI_API_VERSION).body();
    }

    /**
     * Sube archivos a un item de tipo documento. Multipart con la parte 'files' repetida;
     * maximo 20 archivos y 30 MB cada uno. 409 si el RFI esta cerrado, 422 si un archivo
     * no cumple el answer_spec del item.
     */
    public JsonNode uploadRfiDocuments(String rfiId, String itemId, List<KiraFile> files) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        for (KiraFile file : files) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(file.contentType()));
            ByteArrayResource resource = new ByteArrayResource(file.content()) {
                @Override
                public String getFilename() {
                    return file.fileName();
                }
            };
            parts.add("files", new HttpEntity<>(resource, headers));
        }
        return exchangeWithStatus(HttpMethod.POST, rfiDocumentsPath(rfiId, itemId), parts, null,
                RFI_API_VERSION).body();
    }

    /** 422 "The last file cannot be removed": un item respondido necesita al menos un archivo. */
    public JsonNode removeRfiDocument(String rfiId, String itemId, String documentId) {
        return exchangeWithStatus(HttpMethod.DELETE, rfiDocumentsPath(rfiId, itemId) + "/" + documentId,
                null, null, RFI_API_VERSION).body();
    }

    /** La URL es una credencial al portador que caduca en minutos: no se guarda ni se registra. */
    public JsonNode getRfiDocumentLink(String rfiId, String itemId, String documentId) {
        return exchangeWithStatus(HttpMethod.GET, rfiDocumentsPath(rfiId, itemId) + "/" + documentId,
                null, null, RFI_API_VERSION).body();
    }

    private static String rfiDocumentsPath(String rfiId, String itemId) {
        return "/v1/rfis/" + rfiId + "/items/" + itemId + "/documents";
    }

    /** Ruta verificada: /v1/countries. /countries o /api/countries responden 403. */
    public JsonNode listCountries() {
        return exchange(HttpMethod.GET, "/v1/countries", null, null);
    }

    // ---------- Motor de llamadas ----------

    public JsonNode exchange(HttpMethod method, String path, Object body, IdempotencyKey idempotencyKey) {
        return exchangeWithStatus(method, path, body, idempotencyKey).body();
    }

    public KiraResponse exchangeWithStatus(HttpMethod method, String path, Object body,
                                           IdempotencyKey idempotencyKey) {
        return exchangeWithStatus(method, path, body, idempotencyKey, properties.apiVersion());
    }

    private KiraResponse exchangeWithStatus(HttpMethod method, String path, Object body,
                                            IdempotencyKey idempotencyKey, String apiVersion) {
        try {
            return doExchange(method, path, body, idempotencyKey, apiVersion);
        } catch (KiraApiException e) {
            if (!e.isUnauthorized()) {
                throw e;
            }
            // El token expiro o fue revocado: no hay refresh, se reautentica y se reintenta una vez.
            log.info("Kira devolvio 401 en {} {}; reautenticando y reintentando una vez", method, path);
            credentialManager.invalidate();
            return doExchange(method, path, body, idempotencyKey, apiVersion);
        }
    }

    private KiraResponse doExchange(HttpMethod method, String path, Object body,
                                    IdempotencyKey idempotencyKey, String apiVersion) {
        var spec = restClient.method(method)
                .uri(path)
                .header("x-api-key", properties.apiKey())
                .header("Authorization", "Bearer " + credentialManager.getAccessToken())
                .header("X-Api-Version", apiVersion);

        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey.value());
        }
        if (body instanceof MultiValueMap<?, ?>) {
            spec = spec.contentType(MediaType.MULTIPART_FORM_DATA).body(body);
        } else if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }

        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (response.getStatusCode().isError()) {
                throw errorParser.parse(response.getStatusCode().value(), text);
            }
            return new KiraResponse(response.getStatusCode().value(), readTree(text));
        });
    }

    private JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new KiraApiException(200, "unparseable_response",
                    "Respuesta de Kira ilegible: " + e.getMessage(), raw);
        }
    }

    private String withQuery(String path, Map<String, ?> query) {
        if (query == null || query.isEmpty()) {
            return path;
        }
        var builder = UriComponentsBuilder.fromPath(path);
        query.forEach((k, v) -> {
            if (v != null) {
                builder.queryParam(k, v);
            }
        });
        return builder.build().toUriString();
    }
}
