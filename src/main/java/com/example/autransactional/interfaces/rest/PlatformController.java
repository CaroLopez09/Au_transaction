package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.platform.PlatformConsoleService;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Consola de operaciones y cumplimiento de AU: todas las organizaciones, solo lectura. */
@Tag(name = "8. Consola de operaciones", description = "Solo PLATFORM_OPERATOR. Cada ficha consultada queda auditada.")
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
public class PlatformController {

    private final PlatformConsoleService console;

    public PlatformController(PlatformConsoleService console) {
        this.console = console;
    }

    @GetMapping("/tenants")
    public List<PlatformConsoleService.TenantSummary> tenants(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return console.tenants(operator);
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
}
