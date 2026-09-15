package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.auth.LoginUseCase;
import com.example.autransactional.application.auth.MfaService;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "1. Sesion", description = "Login del BFF y perfil del operador. El token de Kira nunca sale del servidor.")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final MfaService mfa;
    private final OperatorUserRepository users;
    private final TenantRepository tenants;

    public AuthController(LoginUseCase loginUseCase, MfaService mfa, OperatorUserRepository users,
                          TenantRepository tenants) {
        this.loginUseCase = loginUseCase;
        this.mfa = mfa;
        this.users = users;
        this.tenants = tenants;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginUseCase.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(loginUseCase.login(request.email(), request.password()));
    }

    /** Paso 2 del inicio de sesion con segundo factor. */
    @PostMapping("/mfa/verify")
    public LoginUseCase.LoginResult verifyMfa(@Valid @RequestBody MfaCodeRequest request) {
        return mfa.verify(request.challenge(), request.code());
    }

    /** Con sesion o con el reto del login (si el entorno exige MFA y la cuenta no lo tiene). */
    @PostMapping("/mfa/setup")
    public MfaService.MfaSetup setupMfa(@AuthenticationPrincipal AuthenticatedOperator operator,
                                        @RequestBody(required = false) MfaChallengeRequest request) {
        return mfa.setup(operator, request == null ? null : request.challenge());
    }

    /** Confirma el secreto con el primer codigo y devuelve una sesion. */
    @PostMapping("/mfa/enable")
    public LoginUseCase.LoginResult enableMfa(@AuthenticationPrincipal AuthenticatedOperator operator,
                                              @Valid @RequestBody MfaEnableRequest request) {
        return mfa.enable(operator, request.challenge(), request.code());
    }

    @PostMapping("/mfa/disable")
    public ResponseEntity<Void> disableMfa(@AuthenticationPrincipal AuthenticatedOperator operator,
                                           @Valid @RequestBody MfaEnableRequest request) {
        mfa.disable(operator, request.code());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal AuthenticatedOperator operator) {
        var user = users.findById(operator.userId());
        String tenantName = operator.role().isPlatform() ? LoginUseCase.PLATFORM_NAME
                : tenants.findById(operator.tenantId()).map(t -> t.getName()).orElse(null);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("userId", operator.userId());
        body.put("email", operator.email());
        body.put("tenantId", operator.tenantId().value());
        // G-02: el nombre de la organizacion sobrevive a una recarga sin otra llamada.
        body.put("tenantName", tenantName);
        body.put("role", operator.role().name());
        body.put("mfaEnabled", user.map(u -> u.mfaEnabled()).orElse(false));
        body.put("mfaEnforced", mfa.isEnforced());
        return ResponseEntity.ok(body);
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record MfaCodeRequest(@NotBlank String challenge, @NotBlank String code) {
    }

    public record MfaChallengeRequest(String challenge) {
    }

    /** `challenge` solo cuando se configura durante el inicio de sesion. */
    public record MfaEnableRequest(String challenge, @NotBlank String code) {
    }
}
