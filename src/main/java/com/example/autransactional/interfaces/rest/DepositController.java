package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.account.DepositView;
import com.example.autransactional.application.account.RecordDepositService;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Depositos entrantes.
 *
 * Solo lectura: los depositos no se crean desde aqui, llegan por webhook. En el sandbox,
 * ademas, este espejo es la unica constancia que existe de ellos.
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

    @GetMapping("/virtual-accounts/{id}/deposits")
    public List<DepositView> listByAccount(@AuthenticationPrincipal AuthenticatedOperator operator,
                                           @PathVariable String id,
                                           @RequestParam(defaultValue = "50") int limit) {
        return deposits.listByAccount(operator, id, limit);
    }
}
