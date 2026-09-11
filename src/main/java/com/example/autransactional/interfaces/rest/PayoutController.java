package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.treasury.PayoutCommands;
import com.example.autransactional.application.treasury.ExecutePayoutService;
import com.example.autransactional.application.treasury.PayoutView;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "2. Pagos", description = "Pagos con control interno maker-checker: quien crea no aprueba.")
@RestController
@RequestMapping("/api/payouts")
public class PayoutController {

    private final ExecutePayoutService payoutService;

    public PayoutController(ExecutePayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @GetMapping
    public List<PayoutView> list(@AuthenticationPrincipal AuthenticatedOperator operator,
                                 @RequestParam(defaultValue = "50") int limit) {
        return payoutService.list(operator, limit);
    }

    @GetMapping("/{id}")
    public PayoutView get(@AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id) {
        return payoutService.get(operator, id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")
    public ResponseEntity<PayoutView> create(@AuthenticationPrincipal AuthenticatedOperator operator,
                                             @Valid @RequestBody PayoutCommands.CreatePayout command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payoutService.create(operator, command));
    }

    /**
     * Aprueba y envia a Kira. La entidad rechaza que el aprobador sea el mismo que lo creo,
     * y la cotizacion debe seguir vigente y con saldo suficiente.
     *
     * El cuerpo es opcional: solo hace falta para la naturaleza del pago, el memo del WIRE
     * y los documentos de soporte.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('TREASURY_APPROVER','ADMIN')")
    public PayoutView approve(@AuthenticationPrincipal AuthenticatedOperator operator,
                              @PathVariable String id,
                              @Valid @RequestBody(required = false) PayoutCommands.ApprovePayout command) {
        return payoutService.approveAndSubmit(operator, id, command);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('TREASURY_APPROVER','ADMIN')")
    public PayoutView reject(@AuthenticationPrincipal AuthenticatedOperator operator,
                             @PathVariable String id,
                             @Valid @RequestBody PayoutCommands.RejectPayout command) {
        return payoutService.reject(operator, id, command.reason());
    }

    @PostMapping("/{id}/refresh")
    public PayoutView refresh(@AuthenticationPrincipal AuthenticatedOperator operator,
                              @PathVariable String id) {
        return payoutService.refreshFromKira(operator, id);
    }
}
