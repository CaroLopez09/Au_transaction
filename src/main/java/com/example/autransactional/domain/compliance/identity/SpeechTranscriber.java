package com.example.autransactional.domain.compliance.identity;

/**
 * Puerto de transcripcion del reto de voz. Recibe la grabacion completa (video+audio),
 * porque el movimiento labial se mide sobre el video y el numero sobre el audio: separarlos
 * permitiria pegar un audio valido sobre un video cualquiera.
 */
public interface SpeechTranscriber {

    Transcription transcribe(byte[] recording, String contentType);
}
