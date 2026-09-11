package com.example.autransactional.interfaces.rest.identity;

import com.example.autransactional.application.compliance.identity.IdentityResponses;
import com.example.autransactional.application.compliance.identity.LivenessUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * Contrato /liveness/* que consume la libreria de onboarding.
 *
 * Lo llama la persona que se esta vinculando, que aun no tiene sesion en el BFF: son
 * endpoints publicos. La organizacion se identifica con clientId y todo queda atado a una
 * sesion de verificacion del servidor.
 */
@Tag(name = "4. Verificacion - prueba de vida", description = "Sesion de liveness y lectura del resultado.")
@RestController
@RequestMapping("/api/v1/liveness")
public class LivenessController {

    private final LivenessUseCase livenessUseCase;

    public LivenessController(LivenessUseCase livenessUseCase) {
        this.livenessUseCase = livenessUseCase;
    }

    @GetMapping("/config")
    public IdentityResponses.LivenessConfig config() {
        return livenessUseCase.config();
    }

    @GetMapping("/status")
    public IdentityResponses.LivenessStatus status() {
        return livenessUseCase.status();
    }

    @PostMapping("/session")
    public IdentityResponses.LivenessSession createSession(
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String verificationId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return livenessUseCase.createSession(clientId, verificationId, correlationId);
    }

    @GetMapping("/session/{sessionId}/result")
    public IdentityResponses.LivenessResult result(@PathVariable String sessionId) {
        return livenessUseCase.result(sessionId);
    }
}
