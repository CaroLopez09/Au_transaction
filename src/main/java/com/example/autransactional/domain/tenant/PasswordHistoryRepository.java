package com.example.autransactional.domain.tenant;

import java.util.List;

/**
 * Historial de hashes de contrasena de un usuario. Solo se guarda el hash (BCrypt), nunca texto
 * plano, para poder aplicar la politica de no reutilizar las ultimas N contrasenas.
 */
public interface PasswordHistoryRepository {

    /** Hashes mas recientes primero, hasta {@code limit} entradas (no incluye la contrasena activa). */
    List<String> recentHashes(String userId, int limit);

    /** Archiva el hash que estaba activo antes de reemplazarlo. */
    void archive(String userId, String passwordHash);

    /** Conserva solo las {@code keep} entradas mas recientes; borra el resto. */
    void trim(String userId, int keep);
}
