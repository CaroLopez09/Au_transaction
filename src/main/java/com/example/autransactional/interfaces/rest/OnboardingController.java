package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.OnboardingCommands;
import com.example.autransactional.application.tenant.OnboardingView;
import com.example.autransactional.application.tenant.SubmitOnboardingService;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Onboarding KYB de la empresa cliente.
 *
 * El portal no debe tener un formulario estatico: GET devuelve 'pendingFields' y esa es
 * la lista de campos que hay que pintar. PUT se repite hasta que quede vacia.
 */
@Tag(name = "1.1 Onboarding KYB",
        description = "Alta de la empresa en Kira y bucle de campos pendientes hasta VERIFIED.")
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final SubmitOnboardingService onboarding;

    public OnboardingController(SubmitOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    /** Estado local, sin llamar a Kira. */
    @GetMapping
    public OnboardingView status(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return onboarding.status(operator);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public ResponseEntity<OnboardingView> register(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody OnboardingCommands.RegisterBusiness command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(onboarding.register(operator, command));
    }

    /** Envia el perfil completo. Se puede repetir; cada llamada reenvia el objeto entero. */
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public OnboardingView completeProfile(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody OnboardingCommands.CompleteProfile command) {
        return onboarding.completeProfile(operator, command);
    }

    /** Relee el recurso en Kira. Cubre el hueco de un webhook que nunca llego. */
    @PostMapping("/refresh")
    public OnboardingView refresh(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return onboarding.refresh(operator);
    }
}
