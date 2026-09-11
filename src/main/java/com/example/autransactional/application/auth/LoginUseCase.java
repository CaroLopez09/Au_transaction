package com.example.autransactional.application.auth;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
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

    public LoginUseCase(OperatorUserRepository users, TenantRepository tenants,
                        PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResult login(String email, String rawPassword) {
        OperatorUser user = users.findByEmail(email)
                .orElseThrow(() -> new DomainException("Credenciales invalidas."));

        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            // Mismo mensaje que el usuario inexistente: no revelamos que cuentas existen.
            throw new DomainException("Credenciales invalidas.");
        }
        user.assertCanLogin();

        Tenant tenant = tenants.findById(user.tenantId())
                .orElseThrow(() -> new DomainException("La organizacion del usuario no existe."));
        tenant.assertActive();

        return new LoginResult(jwtService.issue(user), jwtService.expiresInSeconds(),
                user.email(), user.role().name(), tenant.getId().value(), tenant.getName());
    }

    public record LoginResult(String accessToken, long expiresIn, String email,
                              String role, String tenantId, String tenantName) {
    }
}
