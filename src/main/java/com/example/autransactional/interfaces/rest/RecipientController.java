package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.treasury.RecipientCommands;
import com.example.autransactional.application.treasury.RecipientView;
import com.example.autransactional.application.treasury.RegisterRecipientService;
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
 * Directorio de destinatarios.
 *
 * No hay PUT: Kira no expone actualizacion ni borrado de destinatarios. Para corregir uno
 * se da de alta el reemplazo y se archiva el anterior apuntando al nuevo.
 */
@Tag(name = "2.2 Destinatarios",
        description = "Directorio de destinos de pago. Un destinatario = un riel.")
@RestController
@RequestMapping("/api/recipients")
public class RecipientController {

    private final RegisterRecipientService recipients;

    public RecipientController(RegisterRecipientService recipients) {
        this.recipients = recipients;
    }

    @GetMapping
    public List<RecipientView> list(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return recipients.list(operator);
    }

    @GetMapping("/{id}")
    public RecipientView get(@AuthenticationPrincipal AuthenticatedOperator operator,
                             @PathVariable String id) {
        return recipients.get(operator, id);
    }

    /** `alreadyExisted: true` significa que Kira devolvio un 202: el destino ya estaba. */
    @PostMapping
    @PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")
    public ResponseEntity<RecipientView> register(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody RecipientCommands.RegisterRecipient command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recipients.register(operator, command));
    }

    /** Archiva el destinatario. Con `replacedByRecipientId` queda enlazado a su sustituto. */
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")
    public RecipientView archive(@AuthenticationPrincipal AuthenticatedOperator operator,
                                 @PathVariable String id,
                                 @RequestBody(required = false) RecipientCommands.ArchiveRecipient command) {
        return recipients.archive(operator, id, command);
    }
}
