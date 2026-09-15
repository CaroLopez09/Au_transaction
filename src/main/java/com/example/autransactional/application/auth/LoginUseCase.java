package com.example.autransactional.application.auth;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.infrastructure.security.BffSecurityProperties;
import com.example.autransactional.infrastructure.security.JwtService;
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

    public LoginUseCase(OperatorUserRepository users, TenantRepository tenants,
                        PasswordEncoder passwordEncoder, JwtService jwtService,
                        BffSecurityProperties security) {
        this.users = users;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.security = security;
    }

    public LoginResult login(String email, String rawPassword) {
        OperatorUser user = users.findByEmail(email)
                .orElseThrow(() -> new DomainException("Credenciales invalidas."));

        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            // Mismo mensaje que el usuario inexistente: no revelamos que cuentas existen.
            throw new DomainException("Credenciales invalidas.");
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

    /** Sesion completa para un usuario ya autenticado por todos sus factores. */
    public LoginResult sessionFor(OperatorUser user) {
        return session(user, tenantOf(user));
    }

    private LoginResult session(OperatorUser user, Tenant tenant) {
        return new LoginResult(jwtService.issue(user), jwtService.expiresInSeconds(),
                user.email(), user.role().name(), user.tenantId().value(),
                tenant == null ? PLATFORM_NAME : tenant.getName(), null, null, null);
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
     */
    public record LoginResult(String accessToken, long expiresIn, String email,
                              String role, String tenantId, String tenantName,
                              String mfaChallenge, Boolean mfaRequired, Boolean mfaSetupRequired) {

        static LoginResult challenge(String challenge, long expiresIn, String email, boolean setupRequired) {
            return new LoginResult(null, expiresIn, email, null, null, null, challenge,
                    !setupRequired, setupRequired);
        }
    }
}
