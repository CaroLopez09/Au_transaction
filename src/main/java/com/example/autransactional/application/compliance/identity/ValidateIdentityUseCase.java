package com.example.autransactional.application.compliance.identity;

import com.example.autransactional.domain.compliance.identity.*;
import com.example.autransactional.infrastructure.identity.IdentityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Base64;

/**
 * Emite el veredicto de identidad.
 *
 * Reglas que no se negocian:
 *  - El veredicto sale de aqui, nunca del navegador (recomendacion S-1).
 *  - Las tres pruebas deben pertenecer a la misma sesion de verificacion (recomendacion S-2).
 *  - La cara comparada es el fotograma de la prueba de vida, no una selfie tomada aparte:
 *    asi la cara que se compara es exactamente la que supero la prueba.
 *  - Las imagenes no se persisten (recomendacion S-3): solo veredicto, puntajes y datos del OCR.
 */
@Service
public class ValidateIdentityUseCase {

    private static final Logger log = LoggerFactory.getLogger(ValidateIdentityUseCase.class);

    private final VerificationSessionRepository sessions;
    private final VerificationSessionFactory factory;
    private final FaceComparator faceComparator;
    private final DocumentOcr documentOcr;
    private final VerificationThresholds thresholds;
    private final IdentityProperties properties;

    public ValidateIdentityUseCase(VerificationSessionRepository sessions, VerificationSessionFactory factory,
                                   FaceComparator faceComparator, DocumentOcr documentOcr,
                                   VerificationThresholds thresholds, IdentityProperties properties) {
        this.sessions = sessions;
        this.factory = factory;
        this.faceComparator = faceComparator;
        this.documentOcr = documentOcr;
        this.thresholds = thresholds;
        this.properties = properties;
    }

    public record Command(String verificationId, String challengeId, String livenessSessionId,
                          String countryCode, String documentType, String kiraUserId,
                          byte[] selfieImage, byte[] documentFrontImage, byte[] documentBackImage) {
    }

    @Transactional
    public IdentityResponses.ValidationResult validate(Command command) {
        VerificationSession session = factory.require(command.verificationId());
        Instant now = Instant.now();

        // Ata las tres pruebas: un liveness valido de otra sesion no sirve aqui.
        session.assertReadyForValidation(command.challengeId(), command.livenessSessionId(), now);

        assertSize(command.selfieImage(), "selfieImage");
        assertSize(command.documentFrontImage(), "documentFrontImage");
        assertSize(command.documentBackImage(), "documentBackImage");

        if (command.documentFrontImage() == null || command.documentFrontImage().length == 0) {
            throw new IdentityException(IdentityErrorCode.DOCUMENT_UNREADABLE,
                    "Falta la imagen frontal del documento.");
        }

        session.describeSubject(command.countryCode(), command.documentType(), command.kiraUserId());

        FaceMatch faceMatch = faceComparator.compare(command.selfieImage(), command.documentFrontImage());
        DocumentData documentData = documentOcr.read(command.documentFrontImage(),
                command.documentBackImage(), command.countryCode(), command.documentType());

        VerificationVerdict verdict = session.decide(faceMatch, documentData, thresholds, now);
        sessions.save(session);

        // Sin datos personales ni biometricos en el log: solo la llave y el resultado.
        log.info("Verificacion {} resuelta como {} (liveness={}, match={})",
                session.getId(), verdict, session.getLivenessScore(), session.getMatchScore());

        return new IdentityResponses.ValidationResult(
                session.getId(), verdict, session.getLivenessScore(), session.getMatchScore(),
                documentData, session.getFailureCode() == null ? null : session.getFailureCode().name());
    }

    /** Convierte a bytes el fotograma que el proveedor de liveness devolvio en base64. */
    public static byte[] decodeBase64Image(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        String payload = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void assertSize(byte[] image, String field) {
        if (image != null && image.length > properties.maxUploadBytes()) {
            throw new IdentityException(IdentityErrorCode.SERVER_ERROR,
                    "El archivo " + field + " supera el tamano maximo permitido.");
        }
    }
}
