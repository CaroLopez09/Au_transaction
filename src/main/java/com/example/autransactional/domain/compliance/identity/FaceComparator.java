package com.example.autransactional.domain.compliance.identity;

/** Puerto de comparacion facial 1:1 entre el fotograma del liveness y la foto del documento. */
public interface FaceComparator {

    FaceMatch compare(byte[] livenessFrame, byte[] documentFront);
}
