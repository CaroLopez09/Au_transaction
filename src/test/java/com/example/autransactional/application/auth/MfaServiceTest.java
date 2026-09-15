package com.example.autransactional.application.auth;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.security.BffSecurityProperties;
import com.example.autransactional.infrastructure.security.JwtService;
import com.example.autransactional.infrastructure.security.MfaSecretCipher;
import com.example.autransactional.infrastructure.security.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MfaServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    private final OperatorUserRepository users = mock(OperatorUserRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final Map<String, OperatorUser> almacen = new HashMap<>();

    private BffSecurityProperties props(boolean enforced) {
        return new BffSecurityProperties("secreto-de-pruebas-de-al-menos-32-caracteres", "autransactional-bff",
                3600000L, "clave-mfa-pruebas", enforced, 300000L, "AU Transactional");
    }

    private JwtService jwt;
    private MfaSecretCipher cipher;
    private LoginUseCase login;
    private MfaService mfa;

    private void construir(boolean enforced) {
        BffSecurityProperties p = props(enforced);
        jwt = new JwtService(p);
        cipher = new MfaSecretCipher(p);
        login = new LoginUseCase(users, tenants, ENCODER, jwt, p);
        mfa = new MfaService(users, jwt, cipher, p, login, mock(AuditTrail.class));
    }

    @BeforeEach
    void setUp() {
        when(tenants.findById(TENANT)).thenReturn(Optional.of(new Tenant(TENANT, "Juriscop", "900", "Colombia")));
        guardar(usuario(null, false));
        when(users.findByEmail("ana@juriscop.test")).thenAnswer(i -> Optional.ofNullable(almacen.get("u-1")));
        when(users.findById(anyString())).thenAnswer(i -> Optional.ofNullable(almacen.get((String) i.getArgument(0))));
        doAnswer(i -> {
            OperatorUser u = almacen.get((String) i.getArgument(0));
            guardar(new OperatorUser(u.id(), u.tenantId(), u.email(), u.passwordHash(), u.firstName(), u.lastName(),
                    u.role(), u.status(), i.getArgument(1), i.getArgument(2)));
            return null;
        }).when(users).updateMfa(anyString(), any(), anyBoolean());
        construir(false);
    }

    private OperatorUser usuario(String secret, boolean enabled) {
        return new OperatorUser("u-1", TENANT, "ana@juriscop.test", ENCODER.encode("Dev12345!"), "Ana", "Perez",
                Role.ADMIN, UserStatus.ACTIVE, secret, enabled);
    }

    private void guardar(OperatorUser u) {
        almacen.put(u.id(), u);
    }

    /** Codigo valido ahora para el secreto guardado. */
    private String codigoActual(String secretoCifrado, long desplazamientoPasos) {
        String secret = cipher.decrypt(secretoCifrado);
        long paso = Instant.now().getEpochSecond() / 30 + desplazamientoPasos;
        return codeAt(secret, paso);
    }

    private static String codeAt(String base32, long step) {
        try {
            var method = Totp.class.getDeclaredMethod("codeAt", byte[].class, long.class);
            method.setAccessible(true);
            var decode = Totp.class.getDeclaredMethod("decodeBase32", String.class);
            decode.setAccessible(true);
            return (String) method.invoke(null, decode.invoke(null, base32), step);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String activarMfa() {
        var sesion = login.login("ana@juriscop.test", "Dev12345!");
        var operador = jwt.verify(sesion.accessToken());
        mfa.setup(operador, null);
        mfa.enable(operador, null, codigoActual(almacen.get("u-1").mfaSecret(), 0));
        return almacen.get("u-1").mfaSecret();
    }

    @Test
    void sinSegundoFactorLaContrasenaDaLaSesion() {
        var resultado = login.login("ana@juriscop.test", "Dev12345!");

        assertNotNull(resultado.accessToken());
        assertNull(resultado.mfaChallenge());
    }

    @Test
    void conSegundoFactorLaContrasenaSoloDaUnRetoQueNoSirveComoSesion() {
        activarMfa();

        var resultado = login.login("ana@juriscop.test", "Dev12345!");

        assertNull(resultado.accessToken());
        assertEquals(Boolean.TRUE, resultado.mfaRequired());
        assertThrows(Exception.class, () -> jwt.verify(resultado.mfaChallenge()));
    }

    @Test
    void elSecretoSeGuardaCifrado() {
        String cifrado = activarMfa();

        assertFalse(cifrado.matches("[A-Z2-7]{32}"));
        assertTrue(cipher.decrypt(cifrado).matches("[A-Z2-7]{32}"));
    }

    @Test
    void elRetoMasUnCodigoValidoDanLaSesion() {
        String cifrado = activarMfa();
        var reto = login.login("ana@juriscop.test", "Dev12345!").mfaChallenge();

        var sesion = mfa.verify(reto, codigoActual(cifrado, 1));

        assertNotNull(sesion.accessToken());
        assertEquals("ADMIN", jwt.verify(sesion.accessToken()).role().name());
    }

    @Test
    void unCodigoYaUsadoNoValeOtraVez() {
        String cifrado = activarMfa();
        var reto = login.login("ana@juriscop.test", "Dev12345!").mfaChallenge();
        String codigo = codigoActual(cifrado, 1);
        mfa.verify(reto, codigo);

        var otroReto = login.login("ana@juriscop.test", "Dev12345!").mfaChallenge();
        assertThrows(DomainException.class, () -> mfa.verify(otroReto, codigo));
    }

    @Test
    void cincoCodigosErroneosAgotanElReto() {
        activarMfa();
        var reto = login.login("ana@juriscop.test", "Dev12345!").mfaChallenge();

        for (int i = 0; i < MfaService.MAX_ATTEMPTS; i++) {
            assertThrows(DomainException.class, () -> mfa.verify(reto, "000000"));
        }
        var e = assertThrows(DomainException.class, () -> mfa.verify(reto, "000000"));
        assertTrue(e.getMessage().contains("Demasiados"));
    }

    @Test
    void unOperadorDeLaPlataformaEntraSinEmpresa() {
        guardar(new OperatorUser("platform:operator", TenantId.PLATFORM, "operaciones@au.test",
                ENCODER.encode("Dev12345!"), "Operaciones", "AU", Role.PLATFORM_OPERATOR, UserStatus.ACTIVE, null, false));
        when(users.findByEmail("operaciones@au.test")).thenAnswer(i -> Optional.of(almacen.get("platform:operator")));

        var sesion = login.login("operaciones@au.test", "Dev12345!");

        assertEquals(LoginUseCase.PLATFORM_NAME, sesion.tenantName());
        var operador = jwt.verify(sesion.accessToken());
        assertTrue(operador.tenantId().isPlatform());
        assertEquals(Role.PLATFORM_OPERATOR, operador.role());
    }

    @Test
    void siElEntornoLoExigeQuienNoLoTieneDebeConfigurarloAlEntrar() {
        construir(true);

        var resultado = login.login("ana@juriscop.test", "Dev12345!");
        assertNull(resultado.accessToken());
        assertEquals(Boolean.TRUE, resultado.mfaSetupRequired());

        var alta = mfa.setup(null, resultado.mfaChallenge());
        assertTrue(alta.otpauthUri().startsWith("otpauth://totp/"));
        var sesion = mfa.enable(null, resultado.mfaChallenge(), codigoActual(almacen.get("u-1").mfaSecret(), 0));

        assertNotNull(sesion.accessToken());
        assertTrue(almacen.get("u-1").mfaEnabled());
        assertThrows(DomainException.class, () -> mfa.disable(jwt.verify(sesion.accessToken()), "123456"));
    }
}
