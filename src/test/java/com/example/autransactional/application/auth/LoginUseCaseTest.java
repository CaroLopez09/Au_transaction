package com.example.autransactional.application.auth;

import com.example.autransactional.application.tenant.IdentityVerificationService;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorIdentity;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.PasswordHistoryRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.infrastructure.security.BffSecurityProperties;
import com.example.autransactional.infrastructure.security.JwtService;
import com.example.autransactional.infrastructure.security.PasswordGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Login con contrasena temporal: exige el cambio obligatorio antes de dar sesion. */
class LoginUseCaseTest {

    private static final TenantId TENANT = TenantId.of("juriscop");
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    private final OperatorUserRepository users = mock(OperatorUserRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PasswordHistoryRepository history = mock(PasswordHistoryRepository.class);
    private final Map<String, OperatorUser> almacen = new HashMap<>();

    private JwtService jwt;
    private LoginUseCase login;
    private PasswordService passwords;

    private BffSecurityProperties props() {
        return new BffSecurityProperties("secreto-de-pruebas-de-al-menos-32-caracteres", "autransactional-bff",
                3600000L, "clave-mfa-pruebas", false, 300000L, "AU Transactional");
    }

    @BeforeEach
    void setUp() {
        BffSecurityProperties p = props();
        jwt = new JwtService(p);
        passwords = new PasswordService(users, history, ENCODER, new PasswordGenerator());
        login = new LoginUseCase(users, tenants, ENCODER, jwt, p, null,
                new IdentityVerificationProperties(false), passwords);

        when(tenants.findById(TENANT)).thenReturn(Optional.of(new Tenant(TENANT, "Juriscop", "900", "Colombia")));
        guardar(usuarioConTemporal());
        when(users.findByEmail(anyString())).thenAnswer(i -> almacen.values().stream()
                .filter(u -> u.email().equalsIgnoreCase((String) i.getArgument(0))).findFirst());
        when(users.findById(anyString())).thenAnswer(i -> Optional.ofNullable(almacen.get((String) i.getArgument(0))));
        doAnswer(i -> {
            OperatorUser u = almacen.get((String) i.getArgument(0));
            guardar(new OperatorUser(u.id(), u.tenantId(), u.email(), (String) i.getArgument(1), u.firstName(),
                    u.lastName(), u.role(), u.status(), u.mfaSecret(), u.mfaEnabled(), u.identity(),
                    (Boolean) i.getArgument(2), (Boolean) i.getArgument(3)));
            return null;
        }).when(users).updatePassword(anyString(), anyString(), anyBoolean(), anyBoolean());
        when(history.recentHashes(anyString(), anyInt())).thenReturn(List.of());
    }

    private OperatorUser usuarioConTemporal() {
        return new OperatorUser("u-1", TENANT, "ana@juriscop.test", ENCODER.encode("Temporal123!@#"),
                "Ana", "Perez", Role.TREASURY_APPROVER, UserStatus.ACTIVE, null, false,
                OperatorIdentity.pendingDocuments(), true, false);
    }

    private void guardar(OperatorUser u) {
        almacen.put(u.id(), u);
    }

    @Test
    void unLoginConContrasenaTemporalPideElCambioObligatorio() {
        LoginUseCase.LoginResult resultado = login.login("ana@juriscop.test", "Temporal123!@#");

        assertNull(resultado.accessToken());
        assertNotNull(resultado.passwordChangeChallenge());
    }

    @Test
    void completarElCambioDaUnaSesionYLimpiaLosFlags() {
        LoginUseCase.LoginResult reto = login.login("ana@juriscop.test", "Temporal123!@#");

        LoginUseCase.LoginResult sesion = login.completeMandatoryPasswordChange(
                reto.passwordChangeChallenge(), "Nueva-Clave-Definitiva1!", "Nueva-Clave-Definitiva1!");

        assertNotNull(sesion.accessToken());
        assertEquals("ana@juriscop.test", sesion.email());
        OperatorUser actualizado = almacen.get("u-1");
        assertFalse(actualizado.mustChangePassword());
        assertFalse(actualizado.passwordResetByAdmin());
    }

    @Test
    void lasContrasenasQueNoCoincidenSeRechazan() {
        LoginUseCase.LoginResult reto = login.login("ana@juriscop.test", "Temporal123!@#");

        assertThrows(DomainException.class, () -> login.completeMandatoryPasswordChange(
                reto.passwordChangeChallenge(), "Nueva-Clave-Definitiva1!", "Otra-Diferente2!"));
    }

    @Test
    void unUsuarioSinCambioPendienteInicianSesionNormal() {
        guardar(new OperatorUser("u-1", TENANT, "ana@juriscop.test", ENCODER.encode("Normal123!@#"),
                "Ana", "Perez", Role.TREASURY_APPROVER, UserStatus.ACTIVE, null, false,
                OperatorIdentity.pendingDocuments(), false, false));

        LoginUseCase.LoginResult resultado = login.login("ana@juriscop.test", "Normal123!@#");

        assertNotNull(resultado.accessToken());
        assertNull(resultado.passwordChangeChallenge());
    }
}
