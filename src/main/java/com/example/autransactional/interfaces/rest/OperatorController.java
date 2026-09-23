package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.ManageOperatorsService;
import com.example.autransactional.application.tenant.OperatorCommands;
import com.example.autransactional.application.tenant.OperatorView;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Administracion de los operadores de la propia empresa (G-13).
 *
 * El alcance es siempre la empresa de la sesion: no hay ruta para ver ni tocar los operadores
 * de otra organizacion, ni siquiera indicando su id.
 */
@Tag(name = "9. Operadores",
        description = "Alta, consulta y baja de los usuarios de la propia empresa. Solo ADMIN escribe.")
@RestController
@RequestMapping("/api/operators")
public class OperatorController {

    private final ManageOperatorsService operators;

    public OperatorController(ManageOperatorsService operators) {
        this.operators = operators;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Operadores de la empresa",
            description = "Cumplimiento tambien los ve: necesita saber quien firma cada operacion.")
    public List<OperatorView> list(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return operators.list(operator);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Crear un operador",
            description = "Queda en la empresa de la sesion. No se pueden crear ADMIN ni PLATFORM_OPERATOR.")
    public OperatorView create(@AuthenticationPrincipal AuthenticatedOperator operator,
                               @Valid @RequestBody OperatorCommands.CreateOperator command) {
        return operators.create(operator, command);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Desactivar un operador",
            description = "Suspende la cuenta (no la borra) y no permite la autodesactivacion.")
    public OperatorView suspend(@AuthenticationPrincipal AuthenticatedOperator operator,
                                @PathVariable String id) {
        return operators.suspend(operator, id);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reactivar un operador",
            description = "Revierte una desactivacion. Si la identidad seguia sin verificar, el proximo login vuelve a pedirla.")
    public OperatorView reactivate(@AuthenticationPrincipal AuthenticatedOperator operator,
                                   @PathVariable String id) {
        return operators.reactivate(operator, id);
    }

    @PostMapping("/{id}/relaunch-identity")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Relanzar la verificacion de identidad",
            description = "Solo para identidades rechazadas: limpia el resultado para que la persona vuelva a intentarlo.")
    public OperatorView relaunchIdentity(@AuthenticationPrincipal AuthenticatedOperator operator,
                                        @PathVariable String id) {
        return operators.relaunchIdentity(operator, id);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Restablecer la contrasena de un operador",
            description = "Genera una contrasena temporal aleatoria y la envia por correo. Nunca se devuelve en la respuesta.")
    public void resetPassword(@AuthenticationPrincipal AuthenticatedOperator operator,
                             @PathVariable String id) {
        operators.resetPassword(operator, id);
    }
}
