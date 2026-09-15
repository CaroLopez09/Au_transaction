package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.treasury.PayoutCommands;
import com.example.autransactional.application.treasury.ExecutePayoutService;
import com.example.autransactional.application.treasury.KiraPayoutPage;
import com.example.autransactional.application.treasury.PayoutEventView;
import com.example.autransactional.application.treasury.PayoutPreviewView;
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

    /**
     * Historial de la empresa en Kira, incluidos movimientos que no nacieron en el portal.
     * Pagina por `page` (desde 1) y `limit` (1-100). `status`: CREATED, PENDING, PROCESSING,
     * COMPLETED, FAILED, CANCELLED, IN_REVIEW, KYT_PENDING. Fechas ISO 8601 o AAAA-MM-DD.
     */
    @GetMapping("/kira")
    public KiraPayoutPage kiraHistory(@AuthenticationPrincipal AuthenticatedOperator operator,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int limit,
                                      @RequestParam(required = false) String fromDate,
                                      @RequestParam(required = false) String toDate) {
        return payoutService.kiraHistory(operator, status, page, limit, fromDate, toDate);
    }

    /** Coste del pago sin reservar precio. Para cerrarlo, cotiza en /api/quotations. */
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")
    public PayoutPreviewView preview(@AuthenticationPrincipal AuthenticatedOperator operator,
                                     @Valid @RequestBody PayoutCommands.PreviewPayout command) {
        return payoutService.preview(operator, command);
    }

    @GetMapping("/{id}")
    public PayoutView get(@AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id) {
        return payoutService.get(operator, id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")
    public ResponseEntity<PayoutView> create(@AuthenticationPrincipal AuthenticatedOperator operator,
                                             @RequestHeader(value = "Idempotency-Key", required = false)
                                             String idempotencyKey,
                                             @Valid @RequestBody PayoutCommands.CreatePayout command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payoutService.create(operator, command, idempotencyKey));
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

    /** Renueva la cotizacion vencida de un pago pendiente; devuelve el pago con el precio nuevo. */
    @PostMapping("/{id}/requote")
    @PreAuthorize("hasAnyRole('TREASURY_MAKER','TREASURY_APPROVER','ADMIN')")
    public PayoutView requote(@AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id) {
        return payoutService.requote(operator, id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('TREASURY_APPROVER','ADMIN')")
    public PayoutView reject(@AuthenticationPrincipal AuthenticatedOperator operator,
                             @PathVariable String id,
                             @Valid @RequestBody PayoutCommands.RejectPayout command) {
        return payoutService.reject(operator, id, command.reason());
    }

    /** Linea de tiempo del pago en Kira. Vacia mientras no se haya enviado. */
    @GetMapping("/{id}/events")
    public List<PayoutEventView> events(@AuthenticationPrincipal AuthenticatedOperator operator,
                                        @PathVariable String id) {
        return payoutService.events(operator, id);
    }

    @PostMapping("/{id}/refresh")
    @PreAuthorize("hasAnyRole('ADMIN','TREASURY_MAKER','TREASURY_APPROVER','COMPLIANCE_INTERNAL')")
    public PayoutView refresh(@AuthenticationPrincipal AuthenticatedOperator operator,
                              @PathVariable String id) {
        return payoutService.refreshFromKira(operator, id);
    }
}
