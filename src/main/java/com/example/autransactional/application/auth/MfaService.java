package com.example.autransactional.application.auth;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import com.example.autransactional.infrastructure.security.BffSecurityProperties;
import com.example.autransactional.infrastructure.security.JwtService;
import com.example.autransactional.infrastructure.security.MfaSecretCipher;
import com.example.autransactional.infrastructure.security.Totp;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Segundo factor TOTP (arquitectura §7, "autenticacion fuerte propia").
 *
 * Tres garantias que no dependen del cliente:
 *  - un reto admite como maximo 5 codigos erroneos y despues hay que volver a poner la contrasena;
 *  - un codigo ya usado no vale otra vez dentro de su ventana de 30 s;
 *  - el secreto se guarda cifrado, nunca en claro.
 * Los contadores viven en memoria: con varias instancias del BFF el tope es por instancia.
 */
@Service
public class MfaService {

    static final int MAX_ATTEMPTS = 5;

    private final OperatorUserRepository users;
    private final JwtService jwt;
    private final MfaSecretCipher cipher;
    private final BffSecurityProperties properties;
    private final LoginUseCase login;
    private final AuditTrail audit;

    private final Cache<String, AtomicInteger> failedAttempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10)).build();
    private final Cache<String, Long> lastUsedStep = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2)).build();

    public MfaService(OperatorUserRepository users, JwtService jwt, MfaSecretCipher cipher,
                      BffSecurityProperties properties, LoginUseCase login, AuditTrail audit) {
        this.users = users;
        this.jwt = jwt;
        this.cipher = cipher;
        this.properties = properties;
        this.login = login;
        this.audit = audit;
    }

    /** Paso 2 del inicio de sesion: el reto de la contrasena mas un codigo valido dan la sesion. */
    public LoginUseCase.LoginResult verify(String challengeToken, String code) {
        JwtService.MfaChallenge challenge = readChallenge(challengeToken);
        OperatorUser user = activeUser(challenge.userId());
        if (!user.mfaEnabled()) {
            throw new DomainException("Esta cuenta no tiene segundo factor configurado.");
        }
        assertCode(challenge.challengeId(), user, cipher.decrypt(user.mfaSecret()), code);
        audit.record(actor(user), "auth.mfa_verified", "user", user.id(), null, "OK", user.email());
        return login.sessionFor(user);
    }

    /**
     * Genera un secreto pendiente. Sirve con sesion (activacion voluntaria) o con el reto del login
     * cuando el entorno exige MFA y la cuenta aun no lo tiene.
     */
    public MfaSetup setup(AuthenticatedOperator operator, String challengeToken) {
        OperatorUser user = resolve(operator, challengeToken);
        if (user.mfaEnabled()) {
            throw new DomainException("El segundo factor ya esta activo. Desactivalo antes de configurar otro.");
        }
        String secret = Totp.newSecret();
        users.updateMfa(user.id(), cipher.encrypt(secret), false);
        return new MfaSetup(secret, Totp.otpauthUri(properties.mfaIssuer(), user.email(), secret));
    }

    /** Confirma el secreto pendiente con un primer codigo y devuelve una sesion nueva. */
    public LoginUseCase.LoginResult enable(AuthenticatedOperator operator, String challengeToken, String code) {
        OperatorUser user = resolve(operator, challengeToken);
        if (user.mfaSecret() == null) {
            throw new DomainException("Primero genera el codigo QR del segundo factor.");
        }
        if (user.mfaEnabled()) {
            throw new DomainException("El segundo factor ya esta activo.");
        }
        String attemptKey = challengeToken != null ? jwt.verifyMfaChallenge(challengeToken).challengeId()
                : "setup:" + user.id();
        assertCode(attemptKey, user, cipher.decrypt(user.mfaSecret()), code);
        users.updateMfa(user.id(), user.mfaSecret(), true);
        audit.record(actor(user), "auth.mfa_enabled", "user", user.id(), null, "OK", user.email());
        return login.sessionFor(activeUser(user.id()));
    }

    /** Solo si el entorno no lo exige, y con un codigo valido: robar la sesion no basta para quitarlo. */
    public void disable(AuthenticatedOperator operator, String code) {
        if (properties.mfaEnforced()) {
            throw new DomainException("En este entorno el segundo factor es obligatorio.");
        }
        OperatorUser user = activeUser(operator.userId());
        if (!user.mfaEnabled()) {
            return;
        }
        assertCode("disable:" + user.id(), user, cipher.decrypt(user.mfaSecret()), code);
        users.updateMfa(user.id(), null, false);
        audit.record(actor(user), "auth.mfa_disabled", "user", user.id(), null, "OK", user.email());
    }

    public boolean isEnforced() {
        return properties.mfaEnforced();
    }

    private void assertCode(String attemptKey, OperatorUser user, String secret, String code) {
        AtomicInteger failures = failedAttempts.get(attemptKey, k -> new AtomicInteger());
        if (failures.get() >= MAX_ATTEMPTS) {
            throw new DomainException("Demasiados codigos incorrectos. Vuelve a iniciar sesion.");
        }
        OptionalLong step = Totp.verify(secret, code, Instant.now());
        Long previous = lastUsedStep.getIfPresent(user.id());
        if (step.isEmpty() || (previous != null && step.getAsLong() <= previous)) {
            failures.incrementAndGet();
            audit.record(actor(user), "auth.mfa_failed", "user", user.id(), null, "ERROR", user.email());
            throw new DomainException("El codigo no es valido o ya se uso. Espera al siguiente.");
        }
        lastUsedStep.put(user.id(), step.getAsLong());
        failedAttempts.invalidate(attemptKey);
    }

    /** La bitacora se lee por organizacion: el actor del segundo factor es el propio usuario. */
    private static AuthenticatedOperator actor(OperatorUser user) {
        return new AuthenticatedOperator(user.id(), user.email(), user.tenantId(), user.role());
    }

    private OperatorUser resolve(AuthenticatedOperator operator, String challengeToken) {
        if (operator != null) {
            return activeUser(operator.userId());
        }
        if (challengeToken == null || challengeToken.isBlank()) {
            throw new DomainException("Inicia sesion para configurar el segundo factor.");
        }
        return activeUser(readChallenge(challengeToken).userId());
    }

    private JwtService.MfaChallenge readChallenge(String token) {
        try {
            return jwt.verifyMfaChallenge(token);
        } catch (RuntimeException e) {
            throw new DomainException("El inicio de sesion caduco. Vuelve a escribir tu contrasena.");
        }
    }

    private OperatorUser activeUser(String userId) {
        OperatorUser user = users.findById(userId)
                .orElseThrow(() -> new DomainException("Credenciales invalidas."));
        user.assertCanLogin();
        return user;
    }

    /** El secreto se muestra una sola vez, para escanearlo o escribirlo en la app autenticadora. */
    public record MfaSetup(String secret, String otpauthUri) {
    }
}
