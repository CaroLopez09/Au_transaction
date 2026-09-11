package com.example.autransactional.infrastructure.identity;

import com.example.autransactional.domain.compliance.identity.SpeechTranscriber;
import com.example.autransactional.domain.compliance.identity.Transcription;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transcriptor de desarrollo.
 *
 * No transcribe: extrae el numero de los primeros bytes de la grabacion, de forma que las
 * pruebas y el entorno local puedan enviar un "audio" que contenga el numero esperado y
 * recorrer el camino feliz. Una grabacion que contenga la palabra NOLIPS simula el caso de
 * video pregrabado sin movimiento labial.
 */
public class DevSpeechTranscriber implements SpeechTranscriber {

    private static final Pattern CUATRO_DIGITOS = Pattern.compile("\\d{4}");
    private static final int VENTANA_BYTES = 4096;

    @Override
    public Transcription transcribe(byte[] recording, String contentType) {
        if (recording == null || recording.length == 0) {
            return new Transcription("", null, 0, false);
        }

        String muestra = new String(recording, 0,
                Math.min(recording.length, VENTANA_BYTES), StandardCharsets.ISO_8859_1);

        boolean lipMovement = !muestra.contains("NOLIPS");

        Matcher matcher = CUATRO_DIGITOS.matcher(muestra);
        if (!matcher.find()) {
            return new Transcription(muestra.isBlank() ? "" : "(sin numero reconocible)",
                    null, 0.2, lipMovement);
        }
        String numero = matcher.group();
        return new Transcription("el numero es " + numero, numero, 0.94, lipMovement);
    }
}
