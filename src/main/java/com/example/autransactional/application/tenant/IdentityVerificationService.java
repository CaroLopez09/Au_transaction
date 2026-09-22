package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.FileSignature;
import com.example.autransactional.domain.tenant.IdentityVerificationStatus;
import com.example.autransactional.domain.tenant.OperatorIdentity;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.biometry.BiometryProperties;
import com.example.autransactional.infrastructure.persistence.IdentityVerificationAttemptEntity;
import com.example.autransactional.infrastructure.persistence.IdentityVerificationAttemptJpaRepository;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import com.example.autransactional.infrastructure.security.JwtService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/** Vincula un usuario del portal a una decision biometrica externa sin conservar imagenes en AU. */
@Service
public class IdentityVerificationService {

    private static final long MAX_IMAGE_BYTES = 7L * 1024 * 1024;

    private final OperatorUserRepository users;
    private final IdentityVerificationAttemptJpaRepository attempts;
    private final JwtService jwt;
    private final BiometryProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditTrail audit;
    private final RestClient restClient;

    public IdentityVerificationService(OperatorUserRepository users, IdentityVerificationAttemptJpaRepository attempts,
                                       JwtService jwt, BiometryProperties properties, ObjectMapper objectMapper,
                                       AuditTrail audit, RestClient biometryRestClient) {
        this.users = users;
        this.attempts = attempts;
        this.jwt = jwt;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.restClient = biometryRestClient;
    }

    @Transactional
    public Challenge begin(OperatorUser user) {
        if (user.identity().status().isVerified()) {
            throw new DomainException("La identidad de la cuenta ya esta verificada.");
        }
        if (user.status() == UserStatus.SUSPENDED || user.status() == UserStatus.DISABLED) {
            user.assertCanLogin();
        }
        IdentityVerificationAttemptEntity attempt = new IdentityVerificationAttemptEntity();
        attempt.setId(UUID.randomUUID().toString());
        attempt.setUserId(user.id());
        attempt.setStatus("PENDING");
        attempt.setExpiresAt(Instant.now().plusSeconds(300));
        attempt.setCreatedAt(Instant.now());
        attempts.save(attempt);
        return new Challenge(jwt.issueIdentityChallenge(user, attempt.getId()), user.id(), 300);
    }

    @Transactional
    public Result submit(String challengeToken, String operatorId, MultipartFile documentFront,
                         MultipartFile documentBack, MultipartFile selfie, String documentType,
                         String countryCode, boolean biometricConsent, String livenessSessionId) {
        if (!biometricConsent) {
            throw new DomainException("Debes aceptar el tratamiento de datos biometricos para verificar tu identidad.");
        }
        JwtService.IdentityChallenge challenge = verify(challengeToken);
        if (!challenge.userId().equals(operatorId)) {
            throw new DomainException("El reto de identidad no corresponde a este operador.");
        }
        IdentityVerificationAttemptEntity attempt = attempts.findById(challenge.attemptId())
                .orElseThrow(() -> new DomainException("El reto de identidad no existe."));
        if (!attempt.getUserId().equals(operatorId) || !"PENDING".equals(attempt.getStatus())
                || Instant.now().isAfter(attempt.getExpiresAt())) {
            throw new DomainException("El reto de identidad expiro o ya fue utilizado.");
        }
        OperatorUser user = users.findById(operatorId)
                .orElseThrow(() -> new DomainException("El operador no existe."));
        validateImage(documentFront, "frente del documento");
        validateImage(documentBack, "reverso del documento");
        validateImage(selfie, "selfie de prueba de vida");
        if (!properties.configured()) {
            throw new DomainException("La integracion biometrica no esta configurada en este entorno.");
        }

        attempt.setStatus("SUBMITTED");
        attempt.setChallengeId(challenge.challengeId());
        attempt.setLivenessSessionId(blankToNull(livenessSessionId));
        attempt.setSubmittedAt(Instant.now());
        JsonNode response = callProvider(documentFront, documentBack, selfie, documentType, countryCode, challenge,
                livenessSessionId);
        String decision = text(response, "status", "validationStatus");
        if (decision == null) {
            throw new DomainException("El proveedor biometrico no devolvio una decision.");
        }
        String verificationId = text(response, "verificationId", "verification_id", "id");
        attempt.setProviderVerificationId(verificationId);
        attempt.setDecidedAt(Instant.now());
        IdentityVerificationStatus status = switch (decision.trim().toUpperCase()) {
            case "APPROVED", "VERIFIED" -> IdentityVerificationStatus.VERIFIED;
            case "MANUAL_REVIEW", "IN_REVIEW" -> IdentityVerificationStatus.IN_REVIEW;
            default -> IdentityVerificationStatus.REJECTED;
        };
        attempt.setStatus(status.name());
        attempts.save(attempt);

        Instant now = Instant.now();
        OperatorIdentity identity = new OperatorIdentity(status, null, blankToNull(documentType),
                lastFour(text(response.path("customerDocumentData"), "documentNumber", "number")),
                blankToNull(countryCode), now, now, status.isVerified() ? now : null,
                status == IdentityVerificationStatus.REJECTED ? "El proveedor no aprobo la identidad." : null);
        users.updateIdentity(user.id(), identity, status.isVerified() ? UserStatus.ACTIVE : UserStatus.PENDING_IDENTITY);
        audit.record(actor(user), "operator.identity_verified", "operator_user", user.id(), null,
                status.name(), "verification_id=" + (verificationId == null ? "unavailable" : verificationId));
        return new Result(status.name(), verificationId);
    }

    private JsonNode callProvider(MultipartFile front, MultipartFile back, MultipartFile selfie, String documentType,
                                  String countryCode, JwtService.IdentityChallenge challenge, String livenessSessionId) {
        org.springframework.util.LinkedMultiValueMap<String, Object> parts = new org.springframework.util.LinkedMultiValueMap<>();
        part(parts, "documentFrontImage", front);
        part(parts, "documentBackImage", back);
        part(parts, "selfieImage", selfie);
        parts.add("documentType", documentType == null ? "" : documentType);
        parts.add("countryCode", countryCode == null || countryCode.isBlank() ? "CO" : countryCode);
        parts.add("clientId", challenge.userId());
        try {
            String raw = restClient.post()
                    .uri("/api/v1/identity/validate")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(headers -> {
                        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
                            headers.set("X-Api-Key", properties.apiKey());
                        }
                    })
                    .body(parts).retrieve().body(String.class);
            JsonNode parsed = objectMapper.readTree(raw);
            return parsed.has("data") ? parsed.get("data") : parsed;
        } catch (HttpStatusCodeException e) {
            // El proveedor devuelve 4xx con un mensaje de negocio util (p. ej. "no se detecto un
            // rostro"): lo mostramos al usuario en vez de un mensaje generico que oculta la causa.
            String providerMessage = e.getStatusCode().is4xxClientError() ? extractMessage(e.getResponseBodyAsString()) : null;
            throw new DomainException(providerMessage != null ? providerMessage
                    : "No fue posible validar la identidad con el proveedor.");
        } catch (RuntimeException e) {
            throw new DomainException("No fue posible validar la identidad con el proveedor.");
        }
    }

    private String extractMessage(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode message = node.get("message");
            return message != null && message.isTextual() && !message.asText().isBlank() ? message.asText() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void part(org.springframework.util.LinkedMultiValueMap<String, Object> parts, String name,
                             MultipartFile file) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.getContentType()));
        ByteArrayResource content = new ByteArrayResource(bytes(file)) {
            @Override public String getFilename() { return file.getOriginalFilename(); }
        };
        parts.add(name, new org.springframework.http.HttpEntity<>(content, headers));
    }

    private static void validateImage(MultipartFile file, String label) {
        if (file == null || file.isEmpty()) {
            throw new DomainException("Falta " + label + ".");
        }
        byte[] bytes = bytes(file);
        if (bytes.length > MAX_IMAGE_BYTES || !FileSignature.matches(file.getContentType(), bytes)
                || !MediaType.parseMediaType(file.getContentType()).getType().equals("image")) {
            throw new DomainException("El archivo de " + label + " debe ser una imagen valida de hasta 7 MB.");
        }
    }

    private JwtService.IdentityChallenge verify(String token) {
        try { return jwt.verifyIdentityChallenge(token); }
        catch (RuntimeException e) { throw new DomainException("El reto de identidad es invalido o expiro."); }
    }

    private static byte[] bytes(MultipartFile file) {
        try { return file.getBytes(); }
        catch (java.io.IOException e) { throw new DomainException("No fue posible leer el archivo enviado."); }
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node == null ? null : node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private static String lastFour(String value) {
        if (value == null || value.isBlank()) return null;
        String compact = value.replaceAll("\\s+", "");
        return compact.length() <= 4 ? compact : compact.substring(compact.length() - 4);
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static AuthenticatedOperator actor(OperatorUser user) {
        return new AuthenticatedOperator(user.id(), user.email(), user.tenantId(), user.role());
    }

    public record Challenge(String token, String userId, long expiresInSeconds) { }
    public record Result(String status, String verificationId) { }
}