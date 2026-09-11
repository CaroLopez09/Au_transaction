package com.example.autransactional.interfaces.rest.identity;

import com.example.autransactional.application.compliance.identity.IdentityResponses;
import com.example.autransactional.application.compliance.identity.NumberChallengeUseCase;
import com.example.autransactional.domain.compliance.identity.IdentityErrorCode;
import com.example.autransactional.domain.compliance.identity.IdentityException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** Contrato /number-challenge/* : reto de voz de 4 digitos con deteccion de movimiento labial. */
@Tag(name = "3. Verificacion - reto de voz", description = "Numero de 4 digitos dicho en voz alta, con deteccion de movimiento labial.")
@RestController
@RequestMapping("/api/v1/number-challenge")
public class NumberChallengeController {

    private final NumberChallengeUseCase useCase;

    public NumberChallengeController(NumberChallengeUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/session")
    public IdentityResponses.ChallengeSession createSession(
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String verificationId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return useCase.createSession(clientId, verificationId, correlationId);
    }

    /**
     * Recibe la grabacion completa (video + audio). Va junta a proposito: el numero se valida
     * sobre el audio y el movimiento labial sobre el video, y separarlos permitiria montar un
     * audio valido sobre cualquier video.
     */
    @PostMapping(value = "/{challengeId}/verify", consumes = "multipart/form-data")
    public IdentityResponses.ChallengeVerifyResult verify(@PathVariable String challengeId,
                                                          @RequestPart("recording") MultipartFile recording) {
        try {
            return useCase.verify(challengeId, recording.getBytes(), recording.getContentType());
        } catch (IOException e) {
            throw new IdentityException(IdentityErrorCode.AUDIO_TRANSCRIPTION_FAILED);
        }
    }

    @GetMapping("/{challengeId}/status")
    public IdentityResponses.ChallengeStatus status(@PathVariable String challengeId) {
        return useCase.status(challengeId);
    }
}
