package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.tenant.KybDocumentCommands;
import com.example.autransactional.application.tenant.OnboardingCommands;
import com.example.autransactional.application.tenant.OnboardingView;
import com.example.autransactional.application.tenant.SubmitOnboardingService;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding KYB de la empresa cliente.
 *
 * El portal no debe tener un formulario estatico: GET devuelve 'pendingFields' y esa es
 * la lista de campos que hay que pintar. PUT se repite hasta que quede vacia.
 */
@Tag(name = "1.1 Onboarding KYB",
        description = "Alta de la empresa en Kira y bucle de campos pendientes hasta VERIFIED.")
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final SubmitOnboardingService onboarding;

    public OnboardingController(SubmitOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    /** Estado local, sin llamar a Kira. */
    @GetMapping
    public OnboardingView status(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return onboarding.status(operator);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public ResponseEntity<OnboardingView> register(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody OnboardingCommands.RegisterBusiness command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(onboarding.register(operator, command));
    }

    /** Envia el perfil completo. Se puede repetir; cada llamada reenvia el objeto entero. */
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public OnboardingView completeProfile(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody OnboardingCommands.CompleteProfile command) {
        return onboarding.completeProfile(operator, command);
    }

    /** Relee el recurso en Kira. Cubre el hueco de un webhook que nunca llego. */
    /**
     * Adjunta un registro de identificacion con sus archivos (acta de constitucion, carta
     * EIN, prueba de domicilio...). Kira no tiene endpoint de subida: el archivo viaja
     * dentro del PUT del expediente, en base64, y no se guarda en el BFF.
     *
     * Multipart: `files` repetido, `types` repetido y en el mismo orden (front, back,
     * selfie o file_*), mas `informationType` e `issuingCountry` (ISO-3). Maximo 10
     * archivos y 7 MB en total por peticion; JPEG, PNG o PDF.
     *
     * `types` va como @RequestParam y no como @RequestPart: una parte de texto sin
     * content-type llega como application/octet-stream y @RequestPart no sabe convertirla.
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public OnboardingView attachDocuments(@AuthenticationPrincipal AuthenticatedOperator operator,
                                          @RequestParam String informationType,
                                          @RequestParam String issuingCountry,
                                          @RequestParam(required = false) String number,
                                          @RequestParam(required = false) String expiration,
                                          @RequestPart("files") List<MultipartFile> files,
                                          @RequestParam("types") List<String> types) {
        return onboarding.attachDocuments(operator, new KybDocumentCommands.AttachDocuments(
                informationType, issuingCountry, number, expiration, toDocuments(files, types)));
    }

    /** Terminos vigentes y la version aceptada por la empresa. */
    @GetMapping("/terms")
    public SubmitOnboardingService.TermsView terms(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return onboarding.terms(operator);
    }

    /** Acepta los terminos vigentes; exige el expediente creado en Kira. */
    @PostMapping("/terms")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public SubmitOnboardingService.TermsView acceptTerms(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody OnboardingCommands.AcceptTerms command) {
        return onboarding.acceptTerms(operator, command);
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasAnyRole('ADMIN','TREASURY_MAKER','TREASURY_APPROVER','COMPLIANCE_INTERNAL')")
    public OnboardingView refresh(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return onboarding.refresh(operator);
    }

    /**
     * Empareja por indice la parte `files` con la parte `types`: el archivo i cumple el papel i.
     * Multipart no permite anidar, y esta es la forma mas simple de decir que cada archivo es
     * el anverso, el reverso o la selfie sin inventar un formato propio.
     */
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
