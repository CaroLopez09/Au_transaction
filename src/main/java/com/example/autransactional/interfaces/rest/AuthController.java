package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.auth.LoginUseCase;
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

    public AuthController(LoginUseCase loginUseCase) {
        this.loginUseCase = loginUseCase;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginUseCase.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(loginUseCase.login(request.email(), request.password()));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return ResponseEntity.ok(Map.of(
                "userId", operator.userId(),
                "email", operator.email(),
                "tenantId", operator.tenantId().value(),
                "role", operator.role().name()));
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }
}
