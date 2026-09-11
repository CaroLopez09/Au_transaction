package com.example.autransactional.domain.compliance.identity;

import lombok.Getter;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Reto de voz: el backend muestra un numero de 4 digitos y la persona lo dice en voz alta.
 * Prueba dos cosas a la vez que un video pregrabado no puede fingir: que el numero dicho es
 * el que acabamos de generar, y que hubo movimiento labial sincronizado con el audio.
 *
 * Es de un solo uso y caduca: un reto reutilizable seria un video reutilizable.
 */
@Getter
public class NumberChallenge {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 3;

    private final String id;
    private final String number;
    private final Instant expiresAt;

    private boolean used;
    private int attempts;

    private NumberChallenge(String id, String number, Instant expiresAt, boolean used, int attempts) {
        this.id = id;
        this.number = number;
        this.expiresAt = expiresAt;
        this.used = used;
        this.attempts = attempts;
    }

    public static NumberChallenge issue(String id, Instant expiresAt) {
        // SecureRandom y no Random: el numero es el secreto que hace irrepetible la grabacion.
        String number = String.format("%04d", RANDOM.nextInt(10000));
        return new NumberChallenge(id, number, expiresAt, false, 0);
    }

    public static NumberChallenge rehydrate(String id, String number, Instant expiresAt,
                                            boolean used, int attempts) {
        return new NumberChallenge(id, number, expiresAt, used, attempts);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isUsable(Instant now) {
        return !used && !isExpired(now) && attempts < MAX_ATTEMPTS;
    }

    /**
     * Consume el reto. Se marca usado tanto si acierta como si falla definitivamente,
     * para que un mismo numero no admita una segunda grabacion.
     */
    public void verify(String spokenNumber, boolean lipMovementDetected, Instant now) {
        if (used) {
            throw new IdentityException(IdentityErrorCode.NUMBER_CHALLENGE_ALREADY_USED);
        }
        if (isExpired(now)) {
            throw new IdentityException(IdentityErrorCode.NUMBER_CHALLENGE_EXPIRED);
        }

        attempts++;

        if (spokenNumber == null || spokenNumber.isBlank()) {
            failIfExhausted();
            throw new IdentityException(IdentityErrorCode.AUDIO_TRANSCRIPTION_FAILED);
        }
        if (!lipMovementDetected) {
            // Audio correcto sin labios moviendose es exactamente la senal de un video pregrabado.
            failIfExhausted();
            throw new IdentityException(IdentityErrorCode.LIP_MOVEMENT_NOT_DETECTED);
        }
        if (!number.equals(spokenNumber.trim())) {
            failIfExhausted();
            throw new IdentityException(IdentityErrorCode.INVALID_SPOKEN_NUMBER);
        }

        this.used = true;
    }

    private void failIfExhausted() {
        if (attempts >= MAX_ATTEMPTS) {
            this.used = true;
            throw new IdentityException(IdentityErrorCode.MAX_ATTEMPTS_REACHED);
        }
    }
}
