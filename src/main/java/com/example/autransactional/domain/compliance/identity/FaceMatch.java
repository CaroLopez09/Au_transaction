package com.example.autransactional.domain.compliance.identity;

/** Comparacion 1:1 entre el rostro de la prueba de vida y el del documento. */
public record FaceMatch(double similarity, int facesFoundInDocument) {

    public boolean hasSingleFace() {
        return facesFoundInDocument == 1;
    }
}
