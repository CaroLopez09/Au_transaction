package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.KybDocumentCommands;
import com.example.autransactional.application.tenant.OnboardingView;
import com.example.autransactional.application.tenant.SyncUbosService;
import com.example.autransactional.application.tenant.UboCommands;
import com.example.autransactional.application.tenant.UboView;
import com.example.autransactional.domain.shared.DomainException;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Beneficiarios finales (UBOs) y sus enlaces de prueba de vida.
 *
 * El registro es local primero y se sincroniza en bloque: Kira exige el array completo en
 * cada envio, asi que no hay un "alta de un UBO" contra su API.
 */
@Tag(name = "1.2 Beneficiarios finales",
        description = "UBOs de la empresa, sincronizacion con Kira y enlaces de prueba de vida (7 dias).")
@RestController
@RequestMapping("/api/ubos")
public class UboController {

    private final SyncUbosService ubos;

    public UboController(SyncUbosService ubos) {
        this.ubos = ubos;
    }

    /** Incluye la validacion del grupo: suma de participacion y si hay beneficiario final. */
    @GetMapping
    public UboView.Roster list(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return ubos.list(operator);
    }

    /** Alta o edicion local. Sin `id` crea; con `id` actualiza. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public UboView save(@AuthenticationPrincipal AuthenticatedOperator operator,
                        @Valid @RequestBody UboCommands.SaveUbo command) {
        return ubos.save(operator, command);
    }

    /** Borra un beneficiario que Kira aun no conoce. Devuelve el grupo actualizado. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UboView.Roster delete(@AuthenticationPrincipal AuthenticatedOperator operator,
                                 @PathVariable String id) {
        return ubos.delete(operator, id);
    }

    /** Envia el array completo a Kira. Falla antes de llamar si no hay beneficiario final. */
    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public OnboardingView sync(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return ubos.syncToKira(operator);
    }

    /**
     * Un enlace por beneficiario final. Repetir la llamada devuelve los mismos enlaces
     * mientras no cambien las URLs de redireccion.
     */
    /**
     * Adjunta el documento de identidad de UNA persona (anverso, reverso y selfie).
     *
     * Kira empareja las personas por email, asi que el beneficiario debe tener uno
     * registrado. La selfie junto al documento activa el face match sin sesion interactiva.
     * Mismo multipart que el de la empresa: `files` y `types` emparejados por indice. Con una
     * selfie hace falta `biometricConsent=true`: el consentimiento de la persona queda auditado.
     */
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public UboView attachDocuments(@AuthenticationPrincipal AuthenticatedOperator operator,
                                   @PathVariable String id,
                                   @RequestParam String informationType,
                                   @RequestParam String issuingCountry,
                                   @RequestParam(required = false) String number,
                                   @RequestParam(required = false) String expiration,
                                   @RequestPart("files") List<MultipartFile> files,
                                   @RequestParam("types") List<String> types,
                                   @RequestParam(defaultValue = "false") boolean biometricConsent) {
        return ubos.attachDocuments(operator, id, new KybDocumentCommands.AttachDocuments(
                informationType, issuingCountry, number, expiration, toDocuments(files, types)),
                biometricConsent);
    }

    @PostMapping("/liveness-links")
    @PreAuthorize("hasRole('ADMIN')")
    public UboView.Roster requestLivenessLinks(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestBody(required = false) UboCommands.RequestLivenessLinks command) {
        return ubos.requestLivenessLinks(operator, command);
    }

    /** Empareja por indice `files` con `types`: el archivo i cumple el papel i. */
    private static List<KybDocumentCommands.DocumentFile> toDocuments(List<MultipartFile> files,
                                                                      List<String> types) {
        if (files == null || types == null || files.size() != types.size()) {
            throw new DomainException("Manda una parte `types` por cada parte `files`, en el mismo orden.");
        }
        List<KybDocumentCommands.DocumentFile> documents = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            try {
                documents.add(new KybDocumentCommands.DocumentFile(types.get(i),
                        new KybDocumentCommands.UploadedFile(file.getOriginalFilename(),
                                file.getContentType(), file.getBytes())));
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo leer el archivo recibido.", e);
            }
        }
        return documents;
    }
}
