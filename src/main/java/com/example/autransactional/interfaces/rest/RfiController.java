package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.compliance.AnswerRfiService;
import com.example.autransactional.application.compliance.RfiCommands;
import com.example.autransactional.application.compliance.RfiView;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Bandeja de solicitudes de informacion (RFI) de Kira.
 *
 * No hay POST de alta: Kira genera los RFIs. El portal los sincroniza, los muestra y
 * responde sus items. Un RFI sin atender detiene lo que bloquea hasta que vence.
 */
@Tag(name = "1.3 Solicitudes de informacion (RFI)",
        description = "Requerimientos de Kira: bandeja, sincronizacion y respuesta por item.")
@RestController
@RequestMapping("/api/rfis")
public class RfiController {

    private final AnswerRfiService rfis;

    public RfiController(AnswerRfiService rfis) {
        this.rfis = rfis;
    }

    /** Con `open=true` solo los que admiten respuesta (pending y answered). */
    @GetMapping
    public List<RfiView> list(@AuthenticationPrincipal AuthenticatedOperator operator,
                              @RequestParam(defaultValue = "false") boolean open) {
        return rfis.list(operator, open);
    }

    @GetMapping("/{id}")
    public RfiView get(@AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id) {
        return rfis.get(operator, id);
    }

    /** Trae de Kira los RFIs de la empresa. Red de seguridad del webhook rfi.*. */
    @PostMapping("/sync")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public List<RfiView> sync(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return rfis.sync(operator);
    }

    @PostMapping("/{id}/refresh")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public RfiView refresh(@AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id) {
        return rfis.refresh(operator, id);
    }

    /**
     * Responde items de texto. Es all-or-nothing: un `422` trae `details` por item_id y
     * significa que no se guardo ninguno.
     */
    @PatchMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public RfiView answer(@AuthenticationPrincipal AuthenticatedOperator operator,
                          @PathVariable String id,
                          @Valid @RequestBody RfiCommands.AnswerItems command) {
        return rfis.answer(operator, id, command);
    }
}
