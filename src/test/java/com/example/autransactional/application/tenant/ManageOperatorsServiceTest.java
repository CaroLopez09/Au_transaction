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
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** G-13 / D5: alta, consulta y baja de operadores de la propia empresa. */
class ManageOperatorsServiceTest {

    private final OperatorUserRepository users = mock(OperatorUserRepository.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final TenantId juriscop = TenantId.of("juriscop");
    private final AuthenticatedOperator admin =
            new AuthenticatedOperator("juriscop:admin", "admin@juriscop.test", juriscop, Role.ADMIN);

    private ManageOperatorsService service;

    @BeforeEach
    void setUp() {
        service = new ManageOperatorsService(users, passwordEncoder, audit);
        when(passwordEncoder.encode(any())).thenReturn("$2a$hash");
        when(users.existsByEmail(any())).thenReturn(false);
        when(users.create(any())).thenAnswer(i -> i.getArgument(0));
    }

    private OperatorUser operador(String id, TenantId tenant, Role role, UserStatus status) {
        return new OperatorUser(id, tenant, id + "@test", "$2a$hash", "Ana", "Gomez",
                role, status, null, false);
    }

    private OperatorUser operadorConIdentidad(String id, TenantId tenant, Role role, UserStatus status,
                                              IdentityVerificationStatus identityStatus) {
        return new OperatorUser(id, tenant, id + "@test", "$2a$hash", "Ana", "Gomez",
                role, status, null, false,
                new OperatorIdentity(identityStatus, null, null, null, null, null, null, null, null));
    }

    @Test
    void listaSoloLosOperadoresDeSuEmpresa() {
        when(users.findByTenant(juriscop)).thenReturn(List.of(
                operador("u-1", juriscop, Role.TREASURY_APPROVER, UserStatus.ACTIVE)));

        List<OperatorView> vista = service.list(admin);

        assertEquals(1, vista.size());
        assertEquals("TREASURY_APPROVER", vista.getFirst().role());
        verify(users).findByTenant(juriscop);
    }

    @Test
    void laVistaNuncaExponeElHashNiElSecretoMfa() {
        when(users.findByTenant(juriscop)).thenReturn(List.of(
                new OperatorUser("u-1", juriscop, "ana@juriscop.test", "$2a$secreto", "Ana", "Gomez",
                        Role.TREASURY_APPROVER, UserStatus.ACTIVE, "SECRETO-TOTP", true)));

        OperatorView vista = service.list(admin).getFirst();

        assertFalse(vista.toString().contains("$2a$secreto"));
        assertFalse(vista.toString().contains("SECRETO-TOTP"));
        assertTrue(vista.mfaEnabled());
    }

    @Test
    void creaElOperadorEnLaEmpresaDeLaSesionYConLaClaveCifrada() {
        var command = new OperatorCommands.CreateOperator(
                "Nueva@Juriscop.test", " Ana ", " Gomez ", "contrasena-larga", "TREASURY_APPROVER");

        OperatorView vista = service.create(admin, command);

        ArgumentCaptor<OperatorUser> creado = ArgumentCaptor.forClass(OperatorUser.class);
        verify(users).create(creado.capture());
        OperatorUser usuario = creado.getValue();

        assertEquals(juriscop, usuario.tenantId(), "la empresa sale de la sesion, no del cuerpo");
        assertEquals("nueva@juriscop.test", usuario.email(), "el correo se normaliza");
        assertEquals("Ana", usuario.firstName());
        assertEquals("Gomez", usuario.lastName());
        assertEquals("$2a$hash", usuario.passwordHash());
        assertNotEquals("contrasena-larga", usuario.passwordHash());
        assertEquals(UserStatus.PENDING_IDENTITY, usuario.status());
        assertEquals(com.example.autransactional.domain.tenant.IdentityVerificationStatus.PENDING_DOCUMENTS,
            usuario.identity().status());
        assertFalse(usuario.mfaEnabled());
        assertEquals("TREASURY_APPROVER", vista.role());
        verify(passwordEncoder).encode("contrasena-larga");
        verify(audit).record(eq(admin), eq("operator.created"), eq("operator_user"), any(), any(),
                eq("OK"), any());
    }

    @Test
    void rechazaUnCorreoYaRegistrado() {
        when(users.existsByEmail("ya@juriscop.test")).thenReturn(true);
        var command = new OperatorCommands.CreateOperator(
                "ya@juriscop.test", "Ana", "Gomez", "contrasena-larga", "TREASURY_APPROVER");

        DomainException e = assertThrows(DomainException.class, () -> service.create(admin, command));

        assertTrue(e.getMessage().contains("correo"));
        verify(users, never()).create(any());
    }

    @Test
    void unAdminPuedeCrearOtroAdminPeroNoUnOperadorDePlataforma() {
        var adminCommand = new OperatorCommands.CreateOperator(
                "otro-admin@juriscop.test", "Ana", "Gomez", "contrasena-larga", "ADMIN");
        when(users.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(admin, adminCommand);

        verify(users).create(argThat(nuevo -> nuevo.role() == Role.ADMIN));

        var platformCommand = new OperatorCommands.CreateOperator(
                "operador@juriscop.test", "Ana", "Gomez", "contrasena-larga", "PLATFORM_OPERATOR");

        DomainException e = assertThrows(DomainException.class, () -> service.create(admin, platformCommand));
        assertTrue(e.getMessage().contains("No puedes asignar"), e.getMessage());
    }

    @Test
    void rechazaUnRolInexistente() {
        var command = new OperatorCommands.CreateOperator(
                "ana@juriscop.test", "Ana", "Gomez", "contrasena-larga", "SUPERUSUARIO");

        assertThrows(DomainException.class, () -> service.create(admin, command));
        verify(users, never()).create(any());
    }

    @Test
    void aceptaElUnicoRolDelegable() {
        var command = new OperatorCommands.CreateOperator(
                "treasury.approver@juriscop.test", "Ana", "Gomez", "contrasena-larga", "TREASURY_APPROVER");

        assertEquals("TREASURY_APPROVER", service.create(admin, command).role());
    }

    @Test
    void suspendeAUnOperadorDeSuEmpresa() {
        when(users.findById("u-1")).thenReturn(Optional.of(
                operador("u-1", juriscop, Role.TREASURY_APPROVER, UserStatus.ACTIVE)));

        OperatorView vista = service.suspend(admin, "u-1");

        verify(users).updateStatus("u-1", UserStatus.SUSPENDED);
        assertEquals("SUSPENDED", vista.status());
        assertFalse(vista.active());
        verify(audit).record(eq(admin), eq("operator.suspended"), eq("operator_user"), eq("u-1"),
                any(), eq("OK"), any());
    }

    @Test
    void nadieSeDesactivaASiMismo() {
        DomainException e = assertThrows(DomainException.class,
                () -> service.suspend(admin, "juriscop:admin"));

        assertTrue(e.getMessage().contains("tu propia cuenta"));
        verify(users, never()).updateStatus(any(), any());
    }

    @Test
    void noPuedeTocarAUnOperadorDeOtraEmpresa() {
        when(users.findById("u-9")).thenReturn(Optional.of(
                operador("u-9", TenantId.of("bankvision"), Role.ADMIN, UserStatus.ACTIVE)));

        DomainException e = assertThrows(DomainException.class, () -> service.suspend(admin, "u-9"));

        // Mismo mensaje que si no existiera: no se filtra que usuarios hay en otras empresas.
        assertEquals("El operador no existe.", e.getMessage());
        verify(users, never()).updateStatus(any(), any());
    }

    @Test
    void noSuspendeDosVecesAlMismoOperador() {
        when(users.findById("u-1")).thenReturn(Optional.of(
                operador("u-1", juriscop, Role.TREASURY_APPROVER, UserStatus.SUSPENDED)));

        assertThrows(DomainException.class, () -> service.suspend(admin, "u-1"));
        verify(users, never()).updateStatus(any(), any());
    }

    @Test
    void laConsolaDePlataformaNoEntraPorLasRutasDeEmpresa() {
        var plataforma = new AuthenticatedOperator("platform:operator", "ops@au.test",
                TenantId.PLATFORM, Role.PLATFORM_OPERATOR);

        assertThrows(DomainException.class, () -> service.list(plataforma));
        assertThrows(DomainException.class, () -> service.suspend(plataforma, "u-1"));
        verifyNoInteractions(users);
    }

    @Test
    void reactivaAUnOperadorDesactivado() {
        when(users.findById("u-1")).thenReturn(
                Optional.of(operador("u-1", juriscop, Role.TREASURY_APPROVER, UserStatus.SUSPENDED)),
                Optional.of(operador("u-1", juriscop, Role.TREASURY_APPROVER, UserStatus.ACTIVE)));

        OperatorView vista = service.reactivate(admin, "u-1");

        verify(users).updateStatus("u-1", UserStatus.ACTIVE);
        assertEquals("ACTIVE", vista.status());
        assertTrue(vista.active());
        verify(audit).record(eq(admin), eq("operator.reactivated"), eq("operator_user"), eq("u-1"),
                any(), eq("OK"), any());
    }

    @Test
    void noReactivaAUnOperadorQueYaEstaActivo() {
        when(users.findById("u-1")).thenReturn(Optional.of(
                operador("u-1", juriscop, Role.TREASURY_APPROVER, UserStatus.ACTIVE)));

        assertThrows(DomainException.class, () -> service.reactivate(admin, "u-1"));
        verify(users, never()).updateStatus(any(), any());
    }

    @Test
    void noReactivaAUnOperadorDeOtraEmpresa() {
        when(users.findById("u-9")).thenReturn(Optional.of(
                operador("u-9", TenantId.of("bankvision"), Role.ADMIN, UserStatus.SUSPENDED)));

        DomainException e = assertThrows(DomainException.class, () -> service.reactivate(admin, "u-9"));

        assertEquals("El operador no existe.", e.getMessage());
        verify(users, never()).updateStatus(any(), any());
    }

    @Test
    void relanzaLaVerificacionDeUnaIdentidadRechazada() {
        when(users.findById("u-1")).thenReturn(
                Optional.of(operadorConIdentidad("u-1", juriscop, Role.TREASURY_APPROVER, UserStatus.PENDING_IDENTITY,
                        IdentityVerificationStatus.REJECTED)),
                Optional.of(operadorConIdentidad("u-1", juriscop, Role.TREASURY_APPROVER, UserStatus.PENDING_IDENTITY,
                        IdentityVerificationStatus.PENDING_DOCUMENTS)));

        OperatorView vista = service.relaunchIdentity(admin, "u-1");

        verify(users).updateIdentity(eq("u-1"),
                argThat(identity -> identity.status() == IdentityVerificationStatus.PENDING_DOCUMENTS),
                eq(UserStatus.PENDING_IDENTITY));
        assertEquals("PENDING_DOCUMENTS", vista.identityStatus());
        verify(audit).record(eq(admin), eq("operator.identity_relaunched"), eq("operator_user"), eq("u-1"),
                any(), eq("OK"), any());
    }

    @Test
    void noRelanzaUnaIdentidadQueNoEstaRechazada() {
        when(users.findById("u-1")).thenReturn(Optional.of(
                operadorConIdentidad("u-1", juriscop, Role.TREASURY_APPROVER, UserStatus.ACTIVE,
                        IdentityVerificationStatus.VERIFIED)));

        assertThrows(DomainException.class, () -> service.relaunchIdentity(admin, "u-1"));
        verify(users, never()).updateIdentity(any(), any(), any());
    }
}
