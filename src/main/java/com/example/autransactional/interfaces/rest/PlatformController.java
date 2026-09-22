package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.platform.PlatformConsoleService;
import com.example.autransactional.application.platform.TenantSettingsService;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/** Consola de operaciones y cumplimiento de AU: todas las organizaciones, solo lectura salvo
 *  la parametrizacion por tenant (arquitectura §8), que es la unica escritura de esta consola. */
@Tag(name = "8. Consola de operaciones", description = "Solo PLATFORM_OPERATOR. Cada ficha consultada queda auditada.")
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
public class PlatformController {

    private final PlatformConsoleService console;
    private final TenantSettingsService settings;

    public PlatformController(PlatformConsoleService console, TenantSettingsService settings) {
        this.console = console;
        this.settings = settings;
    }


    @GetMapping("/tenants")
    public List<PlatformConsoleService.TenantSummary> tenants(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return console.tenants(operator);
    }

    @GetMapping("/kira-sandbox-users")
    public List<PlatformConsoleService.KiraSandboxUser> sandboxUsers(
            @AuthenticationPrincipal AuthenticatedOperator operator) {
        return console.sandboxUsers(operator);
    }

    @GetMapping("/tenants/{id}")
    public PlatformConsoleService.Tenant360 tenant(@AuthenticationPrincipal AuthenticatedOperator operator,
                                                   @PathVariable String id) {
        return console.tenant(operator, id);
    }

    @PostMapping("/tenants/{id}/refresh")
    public PlatformConsoleService.Tenant360 refresh(@AuthenticationPrincipal AuthenticatedOperator operator,
                                                    @PathVariable String id) {
        return console.refresh(operator, id);
    }

    @GetMapping("/review-queue")
    public List<PlatformConsoleService.ReviewItem> reviewQueue(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return console.reviewQueue(operator);
    }

    @GetMapping("/tenants/{id}/settings")
    public TenantSettingsService.TenantSettingsView getSettings(
            @AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id) {
        return settings.get(operator, id);
    }

    /** Body: {"enabledRails": ["ACH","WIRE"], "enabledTokens": ["USDC"], "enabledFeatures": ["RFIS"]}.
     *  Reemplaza el set completo de cada tipo. */
    @PutMapping("/tenants/{id}/settings")
    public TenantSettingsService.TenantSettingsView updateSettings(
            @AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id,
            @RequestBody UpdateSettingsRequest body) {
        return settings.update(operator, id, body.enabledRails(), body.enabledTokens(), body.enabledFeatures());
    }

    public record UpdateSettingsRequest(Set<String> enabledRails, Set<String> enabledTokens,
                                        Set<String> enabledFeatures) {
    }
}
