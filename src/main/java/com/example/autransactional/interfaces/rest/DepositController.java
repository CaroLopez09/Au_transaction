package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.account.DepositView;
import com.example.autransactional.application.account.RecordDepositService;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Depositos entrantes.
 *
 * Los depositos no se crean desde aqui: llegan por webhook y, como red de seguridad, se
 * sincronizan desde Kira. En el sandbox el webhook es la unica constancia que existe de ellos.
 */
@Tag(name = "2.4 Depositos",
        description = "Historial de fondeos de las cuentas virtuales, proyectado desde los webhooks.")
@RestController
@RequestMapping("/api")
public class DepositController {

    private final RecordDepositService deposits;

    public DepositController(RecordDepositService deposits) {
        this.deposits = deposits;
    }

    @GetMapping("/deposits")
    public List<DepositView> list(@AuthenticationPrincipal AuthenticatedOperator operator,
                                  @RequestParam(defaultValue = "50") int limit) {
        return deposits.list(operator, limit);
    }

    /** Trae de Kira los depositos de la cuenta y los asienta. Idempotente por id de deposito. */
    @PostMapping("/virtual-accounts/{id}/deposits/sync")
    @PreAuthorize("hasAnyRole('ADMIN','TREASURY_MAKER','TREASURY_APPROVER','COMPLIANCE_INTERNAL')")
    public List<DepositView> syncFromKira(@AuthenticationPrincipal AuthenticatedOperator operator,
                                          @PathVariable String id) {
        return deposits.syncFromKira(operator, id);
    }

    @GetMapping("/virtual-accounts/{id}/deposits")
    public List<DepositView> listByAccount(@AuthenticationPrincipal AuthenticatedOperator operator,
                                           @PathVariable String id,
                                           @RequestParam(defaultValue = "50") int limit) {
        return deposits.listByAccount(operator, id, limit);
    }
}
