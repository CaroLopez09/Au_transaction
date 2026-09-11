package com.example.autransactional.application.compliance.identity;

import com.example.autransactional.domain.compliance.identity.*;
import com.example.autransactional.infrastructure.identity.IdentityProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Reto de voz: emision, verificacion y consulta de estado. */
@Service
public class NumberChallengeUseCase {

    private final VerificationSessionRepository sessions;
    private final VerificationSessionFactory factory;
    private final SpeechTranscriber transcriber;
    private final IdentityProperties properties;

    public NumberChallengeUseCase(VerificationSessionRepository sessions, VerificationSessionFactory factory,
                                  SpeechTranscriber transcriber, IdentityProperties properties) {
        this.sessions = sessions;
        this.factory = factory;
        this.transcriber = transcriber;
        this.properties = properties;
    }

    @Transactional
    public IdentityResponses.ChallengeSession createSession(String clientId, String verificationId,
                                                            String correlationId) {
        VerificationSession session = factory.resolve(clientId, verificationId, correlationId);
        Instant now = Instant.now();

        NumberChallenge challenge = NumberChallenge.issue(
                UUID.randomUUID().toString(), now.plusSeconds(properties.challengeTtlSeconds()));
        session.issueChallenge(challenge, now);
        sessions.save(session);

        return new IdentityResponses.ChallengeSession(challenge.getId(), challenge.getNumber(),
                challenge.getExpiresAt(), session.getId());
    }

    /**
     * Verifica la grabacion. Devuelve un resultado con passed=false y failureReason en vez de
     * lanzar, porque el frontend necesita mostrar el motivo y ofrecer reintento.
     */
    @Transactional
    public IdentityResponses.ChallengeVerifyResult verify(String challengeId, byte[] recording,
                                                          String contentType) {
        VerificationSession session = sessions.findByChallengeId(challengeId)
                .orElseThrow(() -> new IdentityException(IdentityErrorCode.SESSION_NOT_FOUND));

        NumberChallenge challenge = session.requireChallenge(challengeId);
        Instant now = Instant.now();

        Transcription transcription = transcriber.transcribe(recording, contentType);

        try {
            challenge.verify(transcription.normalizedNumber(), transcription.lipMovementDetected(), now);
            session.markChallengePassed(transcription.confidence(), now);
            sessions.save(session);

            return new IdentityResponses.ChallengeVerifyResult(true, transcription.rawText(),
                    transcription.normalizedNumber(), transcription.confidence(), null);

        } catch (IdentityException e) {
            // El intento consumido se persiste igual: si no, reintentar seria gratis.
            sessions.save(session);
            return new IdentityResponses.ChallengeVerifyResult(false, transcription.rawText(),
                    transcription.normalizedNumber(), transcription.confidence(), e.getCode().name());
        }
    }

    @Transactional(readOnly = true)
    public IdentityResponses.ChallengeStatus status(String challengeId) {
        VerificationSession session = sessions.findByChallengeId(challengeId)
                .orElseThrow(() -> new IdentityException(IdentityErrorCode.SESSION_NOT_FOUND));
        NumberChallenge challenge = session.requireChallenge(challengeId);
        Instant now = Instant.now();

        return new IdentityResponses.ChallengeStatus(
                challenge.isUsable(now), challenge.isUsed(), challenge.isExpired(now));
    }
}
