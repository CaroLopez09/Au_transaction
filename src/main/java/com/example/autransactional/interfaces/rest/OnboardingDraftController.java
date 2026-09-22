package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.OnboardingCommands;
import com.example.autransactional.application.tenant.OnboardingDraftService;
import com.example.autransactional.application.tenant.OnboardingDraftView;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Borrador del formulario de vinculacion: permite dejar el KYB a medias y retomarlo.
 * Local al BFF; enviar a Kira sigue siendo POST/PUT /api/onboarding.
 */
@Tag(name = "1.1 Onboarding KYB",
        description = "Alta de la empresa en Kira y bucle de campos pendientes hasta VERIFIED.")
@RestController
@RequestMapping("/api/onboarding/draft")
public class OnboardingDraftController {

    private final OnboardingDraftService drafts;

    public OnboardingDraftController(OnboardingDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping
    public OnboardingDraftView get(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return drafts.get(operator);
    }

    /** Reemplaza el borrador completo. Sin archivos: un data URI se rechaza con 422. */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public OnboardingDraftView save(@AuthenticationPrincipal AuthenticatedOperator operator,
                                    @RequestBody OnboardingCommands.SaveDraft command) {
        return drafts.save(operator, command);
    }
}
