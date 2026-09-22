package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.ImportSandboxTenantService;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entrada controlada para adoptar empresas creadas directamente en el Sandbox de Kira. */
@RestController
@RequestMapping("/api/tenants")
public class TenantImportController {

    private final ImportSandboxTenantService importer;

    public TenantImportController(ImportSandboxTenantService importer) {
        this.importer = importer;
    }

    @PostMapping("/import-sandbox")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public ImportSandboxTenantService.ImportView importSandbox(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody ImportRequest request) {
        return importer.importTenant(operator,
            new ImportSandboxTenantService.ImportCommand(request.kiraUserId(), request.name(), request.taxId(),
                new ImportSandboxTenantService.InitialAdministrator(request.administrator().email(),
                    request.administrator().firstName(), request.administrator().lastName(),
                    request.administrator().password())));
    }

        public record ImportRequest(@NotBlank String kiraUserId, @NotBlank String name, String taxId,
                                    @jakarta.validation.constraints.NotNull @Valid InitialAdministratorRequest administrator) {
        }

        public record InitialAdministratorRequest(@NotBlank @jakarta.validation.constraints.Email String email,
                              @NotBlank String firstName, @NotBlank String lastName,
                              @NotBlank @jakarta.validation.constraints.Size(min = 12, max = 100) String password) {
    }
}