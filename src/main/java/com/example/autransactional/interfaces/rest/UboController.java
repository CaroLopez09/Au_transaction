package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.OnboardingView;
import com.example.autransactional.application.tenant.SyncUbosService;
import com.example.autransactional.application.tenant.UboCommands;
import com.example.autransactional.application.tenant.UboView;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Beneficiarios finales (UBOs) y sus enlaces de prueba de vida.
 *
 * El registro es local primero y se sincroniza en bloque: Kira exige el array completo en
 * cada envio, asi que no hay un "alta de un UBO" contra su API.
 */
@Tag(name = "1.2 Beneficiarios finales",
        description = "UBOs de la empresa, sincronizacion con Kira y enlaces de prueba de vida (7 dias).")
@RestController
@RequestMapping("/api/ubos")
public class UboController {

    private final SyncUbosService ubos;

    public UboController(SyncUbosService ubos) {
        this.ubos = ubos;
    }

    /** Incluye la validacion del grupo: suma de participacion y si hay beneficiario final. */
    @GetMapping
    public UboView.Roster list(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return ubos.list(operator);
    }

    /** Alta o edicion local. Sin `id` crea; con `id` actualiza. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public UboView save(@AuthenticationPrincipal AuthenticatedOperator operator,
                        @Valid @RequestBody UboCommands.SaveUbo command) {
        return ubos.save(operator, command);
    }

    /** Envia el array completo a Kira. Falla antes de llamar si no hay beneficiario final. */
    @PostMapping("/sync")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public OnboardingView sync(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return ubos.syncToKira(operator);
    }

    /**
     * Un enlace por beneficiario final. Repetir la llamada devuelve los mismos enlaces
     * mientras no cambien las URLs de redireccion.
     */
    @PostMapping("/liveness-links")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public UboView.Roster requestLivenessLinks(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestBody(required = false) UboCommands.RequestLivenessLinks command) {
        return ubos.requestLivenessLinks(operator, command);
    }
}
