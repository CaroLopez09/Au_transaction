package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.compliance.AnswerRfiService;
import com.example.autransactional.application.compliance.RfiCommands;
import com.example.autransactional.application.compliance.RfiDocumentLink;
import com.example.autransactional.application.compliance.RfiUboLink;
import com.example.autransactional.application.compliance.RfiView;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    /**
     * Sube archivos a un item de tipo documento: multipart con la parte `files` repetida
     * (maximo 20, 30 MB cada uno; PDF, JPEG, PNG, HEIC o WebP salvo que el item diga otra cosa).
     */
    @PostMapping(value = "/{id}/items/{itemId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public RfiView uploadDocuments(@AuthenticationPrincipal AuthenticatedOperator operator,
                                   @PathVariable String id, @PathVariable String itemId,
                                   @RequestPart("files") List<MultipartFile> files) {
        return rfis.uploadDocuments(operator, id, itemId, files.stream().map(RfiController::toUploaded).toList());
    }

    /** Kira no permite borrar el ultimo archivo de un item ya respondido (422). */
    @DeleteMapping("/{id}/items/{itemId}/documents/{documentId}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public RfiView removeDocument(@AuthenticationPrincipal AuthenticatedOperator operator,
                                  @PathVariable String id, @PathVariable String itemId,
                                  @PathVariable String documentId) {
        return rfis.removeDocument(operator, id, itemId, documentId);
    }

    /**
     * Enlace de verificacion de un beneficiario (item ubo_link). Pedirlo cuando la persona pulsa:
     * caduca en torno a una hora. Si el item ya trae url, se devuelve esa.
     */
    @PostMapping("/{id}/items/{itemId}/ubo-link")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public RfiUboLink mintUboLink(@AuthenticationPrincipal AuthenticatedOperator operator,
                                  @PathVariable String id, @PathVariable String itemId) {
        return rfis.mintUboLink(operator, id, itemId);
    }

    /** Enlace temporal (minutos). Abrirlo al momento; si caduca, pedir otro. */
    @GetMapping("/{id}/items/{itemId}/documents/{documentId}/link")
    public RfiDocumentLink documentLink(@AuthenticationPrincipal AuthenticatedOperator operator,
                                        @PathVariable String id, @PathVariable String itemId,
                                        @PathVariable String documentId) {
        return rfis.documentLink(operator, id, itemId, documentId);
    }

    private static RfiCommands.UploadedFile toUploaded(MultipartFile file) {
        try {
            return new RfiCommands.UploadedFile(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo recibido.", e);
        }
    }
}
