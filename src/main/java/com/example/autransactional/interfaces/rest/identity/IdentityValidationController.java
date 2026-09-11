package com.example.autransactional.interfaces.rest.identity;

import com.example.autransactional.application.compliance.identity.IdentityResponses;
import com.example.autransactional.application.compliance.identity.ValidateIdentityUseCase;
import com.example.autransactional.domain.compliance.identity.IdentityErrorCode;
import com.example.autransactional.domain.compliance.identity.IdentityException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * POST /identity/validate — el paso final del flujo.
 *
 * selfieImage es el fotograma que devolvio la prueba de vida (referenceImageBase64), no una
 * foto tomada aparte. La libreria lo registra como imagen del paso 'liveness' y lo envia aqui.
 *
 * challengeId y livenessSessionId son obligatorios: son lo que impide combinar un liveness
 * valido de una sesion con documentos de otra.
 */
@Tag(name = "5. Verificacion - veredicto", description = "Documento + rostro. El veredicto lo emite el servidor.")
@RestController
@RequestMapping("/api/v1/identity")
public class IdentityValidationController {

    private final ValidateIdentityUseCase useCase;

    public IdentityValidationController(ValidateIdentityUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping(value = "/validate", consumes = "multipart/form-data")
    public IdentityResponses.ValidationResult validate(
            @RequestParam String verificationId,
            @RequestParam String challengeId,
            @RequestParam String livenessSessionId,
            @RequestParam String countryCode,
            @RequestParam String documentType,
            @RequestParam(required = false) String kiraUserId,
            @RequestPart(value = "selfieImage", required = false) MultipartFile selfieImage,
            @RequestPart("documentFrontImage") MultipartFile documentFrontImage,
            @RequestPart(value = "documentBackImage", required = false) MultipartFile documentBackImage) {

        try {
            var command = new ValidateIdentityUseCase.Command(
                    verificationId, challengeId, livenessSessionId, countryCode, documentType, kiraUserId,
                    bytes(selfieImage), bytes(documentFrontImage), bytes(documentBackImage));
            return useCase.validate(command);
        } catch (IOException e) {
            throw new IdentityException(IdentityErrorCode.SERVER_ERROR, "No se pudieron leer las imagenes.");
        }
    }

    private static byte[] bytes(MultipartFile file) throws IOException {
        return file == null || file.isEmpty() ? null : file.getBytes();
    }
}
