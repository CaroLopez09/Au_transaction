package com.example.autransactional.application.auth;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.infrastructure.email.EmailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Recuperacion por OTP: codigo y token internos (fallback), no el proveedor externo. */
class ForgotPasswordServiceTest {

    private final OperatorUserRepository users = mock(OperatorUserRepository.class);
    private final EmailNotificationService email = mock(EmailNotificationService.class);
    private final PasswordService passwords = mock(PasswordService.class);

    private final TenantId juriscop = TenantId.of("juriscop");
    private final OperatorUser user = new OperatorUser("u-1", juriscop, "ana@juriscop.test", "$2a$hash",
            "Ana", "Gomez", Role.TREASURY_APPROVER, UserStatus.ACTIVE, null, false);

    private ForgotPasswordService service;

    @BeforeEach
    void setUp() {
        service = new ForgotPasswordService(users, email, passwords);
        when(users.findByEmail("ana@juriscop.test")).thenReturn(Optional.of(user));
        when(users.findById("u-1")).thenReturn(Optional.of(user));
    }

    @Test
    void flujoCompletoDeRecuperacionFunciona() {
        service.start("Ana@Juriscop.test");

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(email).sendRecoveryOtpEmail(eq("ana@juriscop.test"), any(), otpCaptor.capture());
        String otp = otpCaptor.getValue();
        assertEquals(6, otp.length());

        String resetToken = service.verify("ana@juriscop.test", otp);
        assertNotNull(resetToken);

        service.reset(resetToken, "Nueva-Clave-Segura1!", "Nueva-Clave-Segura1!");

        verify(passwords).applyChosenPassword(user, "Nueva-Clave-Segura1!");
    }

    @Test
    void unOtpIncorrectoNoEmiteToken() {
        service.start("ana@juriscop.test");

        assertThrows(DomainException.class, () -> service.verify("ana@juriscop.test", "000000"));
    }

    @Test
    void unOtpNoSePuedeReutilizarUnaSegundaVez() {
        service.start("ana@juriscop.test");
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(email).sendRecoveryOtpEmail(eq("ana@juriscop.test"), any(), otpCaptor.capture());
        String otp = otpCaptor.getValue();

        service.verify("ana@juriscop.test", otp);

        assertThrows(DomainException.class, () -> service.verify("ana@juriscop.test", otp));
    }

    @Test
    void unTokenDeResetNoSePuedeReutilizar() {
        service.start("ana@juriscop.test");
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(email).sendRecoveryOtpEmail(eq("ana@juriscop.test"), any(), otpCaptor.capture());
        String resetToken = service.verify("ana@juriscop.test", otpCaptor.getValue());

        service.reset(resetToken, "Nueva-Clave-Segura1!", "Nueva-Clave-Segura1!");

        assertThrows(DomainException.class,
                () -> service.reset(resetToken, "Otra-Clave-Segura2!", "Otra-Clave-Segura2!"));
    }

    @Test
    void contrasenasQueNoCoincidenSeRechazan() {
        service.start("ana@juriscop.test");
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(email).sendRecoveryOtpEmail(eq("ana@juriscop.test"), any(), otpCaptor.capture());
        String resetToken = service.verify("ana@juriscop.test", otpCaptor.getValue());

        assertThrows(DomainException.class,
                () -> service.reset(resetToken, "Nueva-Clave-Segura1!", "Otra-Diferente2!"));
    }

    @Test
    void unCorreoInexistenteNoRevelaNadaYNoEnviaCorreo() {
        when(users.findByEmail("nadie@juriscop.test")).thenReturn(Optional.empty());

        service.start("nadie@juriscop.test");

        verifyNoInteractions(email);
    }
}
