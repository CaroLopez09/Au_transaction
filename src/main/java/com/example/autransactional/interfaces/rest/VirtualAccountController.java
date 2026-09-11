package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.account.OpenVirtualAccountService;
import com.example.autransactional.application.account.VirtualAccountCommands;
import com.example.autransactional.application.account.VirtualAccountView;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Cuentas virtuales.
 *
 * 'fundsReady' es la unica senal fiable de que la cuenta puede mover fondos; el estado por
 * si solo no basta. Si 'activationDelayed' es true, la activacion lleva demasiado tiempo y
 * el portal debe ofrecer contactar con Kira en vez de seguir esperando.
 */
@Tag(name = "2.3 Cuentas virtuales",
        description = "Apertura, activacion y saldo de las cuentas en bancos de EE. UU.")
@RestController
@RequestMapping("/api/virtual-accounts")
public class VirtualAccountController {

    private final OpenVirtualAccountService accounts;

    public VirtualAccountController(OpenVirtualAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<VirtualAccountView> list(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return accounts.list(operator);
    }

    @GetMapping("/{id}")
    public VirtualAccountView get(@AuthenticationPrincipal AuthenticatedOperator operator,
                                  @PathVariable String id) {
        return accounts.get(operator, id);
    }

    /** Exige KYB VERIFIED y producto elegible. Un 409 de Kira reutiliza la cuenta existente. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','TREASURY_MAKER','COMPLIANCE_INTERNAL')")
    public ResponseEntity<VirtualAccountView> open(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody VirtualAccountCommands.OpenAccount command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.open(operator, command));
    }

    /** Relee la cuenta. Cubre el hueco de un virtual_account.activated que nunca llego. */
    @PostMapping("/{id}/refresh")
    public VirtualAccountView refresh(@AuthenticationPrincipal AuthenticatedOperator operator,
                                      @PathVariable String id) {
        return accounts.refresh(operator, id);
    }

    @PostMapping("/{id}/balance")
    public VirtualAccountView refreshBalance(@AuthenticationPrincipal AuthenticatedOperator operator,
                                             @PathVariable String id) {
        return accounts.refreshBalance(operator, id);
    }

    /** Solo sandbox: en produccion responde 422 sin llamar a Kira. */
    @PostMapping("/{id}/simulate-deposit")
    @PreAuthorize("hasAnyRole('ADMIN','TREASURY_MAKER')")
    public VirtualAccountView simulateDeposit(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String id,
            @Valid @RequestBody VirtualAccountCommands.SimulateDeposit command) {
        return accounts.simulateDeposit(operator, id, command);
    }
}
