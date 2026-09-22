package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.IdentityVerificationStatus;
import com.example.autransactional.domain.tenant.OperatorIdentity;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.biometry.BiometryProperties;
import com.example.autransactional.infrastructure.persistence.IdentityVerificationAttemptEntity;
import com.example.autransactional.infrastructure.persistence.IdentityVerificationAttemptJpaRepository;
import com.example.autransactional.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * G-24: verificacion de identidad de un operador contra el servicio biometrico
 * (documento frente/reverso + selfie -> POST /api/v1/identity/validate).
 */
class IdentityVerificationServiceTest {

    private static final byte[] JPEG_HEADER = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01};

    private final OperatorUserRepository users = mock(OperatorUserRepository.class);
    private final IdentityVerificationAttemptJpaRepository attempts = mock(IdentityVerificationAttemptJpaRepository.class);
    private final JwtService jwt = mock(JwtService.class);
    private final AuditTrail audit = mock(AuditTrail.class);

    private final TenantId juriscop = TenantId.of("juriscop");
    private final OperatorUser operador = new OperatorUser("op-1", juriscop, "ana@juriscop.test", "$2a$hash",
            "Ana", "Gomez", Role.ADMIN, UserStatus.PENDING_IDENTITY, null, false,
            OperatorIdentity.pendingDocuments());

    private MockRestServiceServer server;
    private IdentityVerificationService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://biometria.test");
        server = MockRestServiceServer.bindTo(builder).build();
        BiometryProperties properties = new BiometryProperties("https://biometria.test", "api-key-123");

        service = new IdentityVerificationService(users, attempts, jwt, properties, new ObjectMapper(), audit,
                builder.build());

        when(jwt.verifyIdentityChallenge("challenge-token")).thenReturn(
                new JwtService.IdentityChallenge("op-1", "attempt-1", "chal-1"));
        IdentityVerificationAttemptEntity attempt = new IdentityVerificationAttemptEntity();
        attempt.setId("attempt-1");
        attempt.setUserId("op-1");
        attempt.setStatus("PENDING");
        attempt.setExpiresAt(Instant.now().plusSeconds(120));
        when(attempts.findById("attempt-1")).thenReturn(Optional.of(attempt));
        when(users.findById("op-1")).thenReturn(Optional.of(operador));
    }

    private MultipartFile image(String name) {
        return new MockMultipartFile(name, name + ".jpg", "image/jpeg", JPEG_HEADER);
    }

    @Test
    void unaSimilitudAltaAprueltaLaIdentidadYActivaLaCuenta() {
        server.expect(requestTo("https://biometria.test/api/v1/identity/validate"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        { "verificationId": "ver-1", "similarity": 96.4, "status": "APPROVED",
                          "customerDocumentData": { "documentNumber": "1234567890" } }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        var result = service.submit("challenge-token", "op-1", image("front"), image("back"), image("selfie"),
                "CC", "CO", true, null);

        assertEquals("VERIFIED", result.status());
        assertEquals("ver-1", result.verificationId());
        verify(users).updateIdentity(eq("op-1"), any(), eq(UserStatus.ACTIVE));
        server.verify();
    }

    @Test
    void unaSimilitudBajaRechazaLaIdentidadYLaCuentaQuedaPendiente() {
        server.expect(requestTo("https://biometria.test/api/v1/identity/validate"))
                .andRespond(withSuccess("""
                        { "verificationId": "ver-2", "similarity": 40.0, "status": "REJECTED" }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        var result = service.submit("challenge-token", "op-1", image("front"), image("back"), image("selfie"),
                "CC", "CO", true, null);

        assertEquals("REJECTED", result.status());
        verify(users).updateIdentity(eq("op-1"), any(), eq(UserStatus.PENDING_IDENTITY));
    }

    @Test
    void sinConsentimientoBiometricoNoLlamaAlProveedor() {
        assertThrows(DomainException.class, () -> service.submit("challenge-token", "op-1",
                image("front"), image("back"), image("selfie"), "CC", "CO", false, null));
        server.verify();
    }

    @Test
    void unRechazoIncrementaElContadorYDevuelveLosIntentosRestantes() {
        server.expect(requestTo("https://biometria.test/api/v1/identity/validate"))
                .andRespond(withSuccess("""
                        { "verificationId": "ver-3", "status": "REJECTED" }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        var result = service.submit("challenge-token", "op-1", image("front"), image("back"), image("selfie"),
                "CC", "CO", true, null);

        assertEquals("REJECTED", result.status());
        assertEquals(2, result.remainingAttempts());
        verify(users).updateIdentity(eq("op-1"),
                org.mockito.ArgumentMatchers.argThat(identity -> identity.rejectedAttempts() == 1),
                eq(UserStatus.PENDING_IDENTITY));
    }

    @Test
    void unaVerificacionAprobadaReiniciaElContadorDeRechazos() {
        OperatorUser conDosRechazosPrevios = new OperatorUser("op-1", juriscop, "ana@juriscop.test", "$2a$hash",
                "Ana", "Gomez", Role.ADMIN, UserStatus.PENDING_IDENTITY, null, false,
                new OperatorIdentity(IdentityVerificationStatus.REJECTED, null, null, null, null,
                        null, null, null, "rechazo previo", 2));
        when(users.findById("op-1")).thenReturn(Optional.of(conDosRechazosPrevios));
        server.expect(requestTo("https://biometria.test/api/v1/identity/validate"))
                .andRespond(withSuccess("""
                        { "verificationId": "ver-4", "status": "APPROVED" }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        var result = service.submit("challenge-token", "op-1", image("front"), image("back"), image("selfie"),
                "CC", "CO", true, null);

        assertEquals("VERIFIED", result.status());
        verify(users).updateIdentity(eq("op-1"),
                org.mockito.ArgumentMatchers.argThat(identity -> identity.rejectedAttempts() == 0),
                eq(UserStatus.ACTIVE));
    }

    @Test
    void begin_bloqueaNuevosRetosTrasAgotarLosTresIntentos() {
        OperatorUser sinMasIntentos = new OperatorUser("op-1", juriscop, "ana@juriscop.test", "$2a$hash",
                "Ana", "Gomez", Role.ADMIN, UserStatus.PENDING_IDENTITY, null, false,
                new OperatorIdentity(IdentityVerificationStatus.REJECTED, null, null, null, null,
                        null, null, null, "rechazo previo", 3));

        DomainException e = assertThrows(DomainException.class, () -> service.begin(sinMasIntentos));

        assertTrue(e.getMessage().contains("administrador"));
    }

    @Test
    void begin_permiteUnCuartoRetoSiAunNoLlegaAlTope() {
        OperatorUser conDosRechazos = new OperatorUser("op-1", juriscop, "ana@juriscop.test", "$2a$hash",
                "Ana", "Gomez", Role.ADMIN, UserStatus.PENDING_IDENTITY, null, false,
                new OperatorIdentity(IdentityVerificationStatus.REJECTED, null, null, null, null,
                        null, null, null, "rechazo previo", 2));

        var challenge = service.begin(conDosRechazos);

        assertEquals("op-1", challenge.userId());
        verify(attempts).save(any());
    }
}
