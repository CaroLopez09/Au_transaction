package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.treasury.CreateQuoteService;
import com.example.autransactional.application.treasury.QuotationCommands;
import com.example.autransactional.application.treasury.QuotationView;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Cotizaciones de transferencia.
 *
 * Viven 15 minutos exactos. La respuesta trae 'secondsToExpiry' para el contador: al
 * llegar a cero hay que recotizar, no reutilizar.
 */
@Tag(name = "2.1 Cotizaciones",
        description = "Precio en firme de una transferencia: desglose de comisiones y TTL de 15 minutos.")
@RestController
@RequestMapping("/api/quotations")
public class QuotationController {

    private final CreateQuoteService quotes;

    public QuotationController(CreateQuoteService quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/{id}")
    public QuotationView get(@AuthenticationPrincipal AuthenticatedOperator operator,
                             @PathVariable String id) {
        return quotes.get(operator, id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuotationView> create(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody QuotationCommands.CreateQuote command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(quotes.create(operator, command));
    }
}
