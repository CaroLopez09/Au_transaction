package com.example.autransactional.application.auth;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.infrastructure.security.BffSecurityProperties;
import com.example.autransactional.infrastructure.security.JwtService;
import com.example.autransactional.application.tenant.IdentityVerificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Sesion propia del BFF. Las credenciales de Kira nunca salen del servidor. */
@Service
public class LoginUseCase {

    private final OperatorUserRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final BffSecurityProperties security;
    private final IdentityVerificationService identity;
    private final IdentityVerificationProperties identityVerificationProperties;
    private final PasswordService passwords;

    @Autowired
    public LoginUseCase(OperatorUserRepository users, TenantRepository tenants,
                        PasswordEncoder passwordEncoder, JwtService jwtService,
                        BffSecurityProperties security, IdentityVerificationService identity,
                        IdentityVerificationProperties identityVerificationProperties,
                        PasswordService passwords) {
        this.users = users;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.security = security;
        this.identity = identity;
        this.identityVerificationProperties = identityVerificationProperties;
        this.passwords = passwords;
    }

    /** Compatibilidad para pruebas unitarias que construyen el caso de uso sin Spring. */
    public LoginUseCase(OperatorUserRepository users, TenantRepository tenants,
                        PasswordEncoder passwordEncoder, JwtService jwtService,
                        BffSecurityProperties security) {
        this(users, tenants, passwordEncoder, jwtService, security, null,
                new IdentityVerificationProperties(true), null);
    }

    public LoginResult login(String email, String rawPassword) {
        OperatorUser user = users.findByEmail(email)
                .orElseThrow(() -> new DomainException("Credenciales invalidas."));

        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            // Mismo mensaje que el usuario inexistente: no revelamos que cuentas existen.
            throw new DomainException("Credenciales invalidas.");
        }
        if (identity != null && identityVerificationProperties.enabled() && !user.identity().status().isVerified()) {
            IdentityVerificationService.Challenge challenge = identity.begin(user);
            return LoginResult.identity(challenge.token(), challenge.userId(), challenge.expiresInSeconds(), user.email());
        }
        // La contrasena temporal (alta o reset administrativo) bloquea cualquier sesion hasta que
        // la persona elija una definitiva. Va despues de la identidad: ese reto ya es obligatorio
        // y no tiene sentido pedir dos pantallas bloqueantes en el orden equivocado.
        if (user.mustChangePassword()) {
            return LoginResult.passwordChange(jwtService.issuePasswordChangeChallenge(user),
                    jwtService.mfaChallengeExpiresInSeconds(), user.email());
        }
        user.assertCanLogin();
        Tenant tenant = tenantOf(user);

        // Con segundo factor la contrasena no da sesion: solo un reto de pocos minutos.
        if (user.mfaEnabled()) {
            return LoginResult.challenge(jwtService.issueMfaChallenge(user),
                    jwtService.mfaChallengeExpiresInSeconds(), user.email(), false);
        }
        if (security.mfaEnforced()) {
            return LoginResult.challenge(jwtService.issueMfaChallenge(user),
                    jwtService.mfaChallengeExpiresInSeconds(), user.email(), true);
        }
        return session(user, tenant);
    }

    /**
     * Completa el cambio obligatorio de contrasena tras un login con contrasena temporal y
     * continua el mismo pipeline de login (MFA si aplica, o sesion completa).
     */
    public LoginResult completeMandatoryPasswordChange(String challengeToken, String newPassword,
                                                       String confirmNewPassword) {
        if (newPassword == null || !newPassword.equals(confirmNewPassword)) {
            throw new DomainException("Las contrasenas no coinciden.");
        }
        JwtService.PasswordChangeChallenge challenge = verifyPasswordChangeChallenge(challengeToken);
        OperatorUser user = users.findById(challenge.userId())
                .orElseThrow(() -> new DomainException("El usuario no existe."));
        if (!user.mustChangePassword()) {
            throw new DomainException("Esta cuenta no tiene un cambio de contrasena pendiente.");
        }

        passwords.applyChosenPassword(user, newPassword);

        OperatorUser actualizado = users.findById(user.id())
                .orElseThrow(() -> new DomainException("El usuario no existe."));
        actualizado.assertCanLogin();
        Tenant tenant = tenantOf(actualizado);

        if (actualizado.mfaEnabled()) {
            return LoginResult.challenge(jwtService.issueMfaChallenge(actualizado),
                    jwtService.mfaChallengeExpiresInSeconds(), actualizado.email(), false);
        }
        if (security.mfaEnforced()) {
            return LoginResult.challenge(jwtService.issueMfaChallenge(actualizado),
                    jwtService.mfaChallengeExpiresInSeconds(), actualizado.email(), true);
        }
        return session(actualizado, tenant);
    }

    private JwtService.PasswordChangeChallenge verifyPasswordChangeChallenge(String token) {
        try {
            return jwtService.verifyPasswordChangeChallenge(token);
        } catch (RuntimeException e) {
            throw new DomainException("El reto de cambio de contrasena expiro o no es valido.");
        }
    }

    /** Sesion completa para un usuario ya autenticado por todos sus factores. */
    public LoginResult sessionFor(OperatorUser user) {
        return session(user, tenantOf(user));
    }

    private LoginResult session(OperatorUser user, Tenant tenant) {
        return new LoginResult(jwtService.issue(user), jwtService.expiresInSeconds(),
            user.email(), user.role().name(), user.tenantId().value(),
            tenant == null ? PLATFORM_NAME : tenant.getName(), null, null, null, null, null, null);
    }

    public static final String PLATFORM_NAME = "AU Transactional · Operaciones";

    /** Null para un operador de la plataforma, que no pertenece a ninguna empresa. */
    private Tenant tenantOf(OperatorUser user) {
        if (user.role().isPlatform()) {
            return null;
        }
        Tenant tenant = tenants.findById(user.tenantId())
                .orElseThrow(() -> new DomainException("La organizacion del usuario no existe."));
        tenant.assertActive();
        return tenant;
    }

    /**
     * Con MFA, la primera respuesta no trae accessToken sino mfaChallenge: mfaRequired pide el codigo
     * y mfaSetupRequired pide configurar el segundo factor antes (entorno que lo exige).
     * passwordChangeChallenge aparece cuando la cuenta tiene una contrasena temporal pendiente de
     * cambio (alta o reset administrativo): el front debe mostrar la pantalla obligatoria antes de
     * seguir con MFA o con la sesion.
     */
    public record LoginResult(String accessToken, long expiresIn, String email,
                              String role, String tenantId, String tenantName,
                              String mfaChallenge, Boolean mfaRequired, Boolean mfaSetupRequired,
                              String identityChallenge, String identityUserId,
                              String passwordChangeChallenge) {

        static LoginResult challenge(String challenge, long expiresIn, String email, boolean setupRequired) {
                return new LoginResult(null, expiresIn, email, null, null, null, challenge,
                    !setupRequired, setupRequired, null, null, null);
            }

            static LoginResult identity(String challenge, String userId, long expiresIn, String email) {
                return new LoginResult(null, expiresIn, email, null, null, null, null,
                    null, null, challenge, userId, null);
        }

            static LoginResult passwordChange(String challenge, long expiresIn, String email) {
                return new LoginResult(null, expiresIn, email, null, null, null, null,
                    null, null, null, null, challenge);
        }
    }
}
