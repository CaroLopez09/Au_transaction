package com.example.autransactional.infrastructure.identity;

import com.example.autransactional.domain.compliance.identity.FaceComparator;
import com.example.autransactional.domain.compliance.identity.FaceMatch;

/**
 * Comparador facial de desarrollo. Devuelve una similitud alta y estable cuando ambas
 * imagenes tienen contenido, y una baja cuando el documento viene vacio, para poder
 * recorrer los tres veredictos sin proveedor real.
 */
public class DevFaceComparator implements FaceComparator {

    private static final int MINIMO_BYTES_UTILES = 64;

    @Override
    public FaceMatch compare(byte[] livenessFrame, byte[] documentFront) {
        if (documentFront == null || documentFront.length < MINIMO_BYTES_UTILES) {
            return new FaceMatch(0, 0);
        }
        if (livenessFrame == null || livenessFrame.length < MINIMO_BYTES_UTILES) {
            return new FaceMatch(0, 1);
        }
        // Determinista: el mismo par de imagenes siempre da el mismo puntaje.
        int semilla = Math.abs((livenessFrame.length * 31 + documentFront.length) % 100);
        double similitud = 88 + (semilla % 10);
        return new FaceMatch(similitud, 1);
    }
}
