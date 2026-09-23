package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

public interface OperatorUserRepository {

    Optional<OperatorUser> findByEmail(String email);

    Optional<OperatorUser> findById(String id);

    List<OperatorUser> findByTenant(TenantId tenantId);

    /** Guarda el secreto TOTP ya cifrado y si esta activo. Un secreto nulo quita el segundo factor. */
    void updateMfa(String userId, String encryptedSecret, boolean enabled);

    /** true si el correo ya esta tomado: la tabla `users` lo exige unico en toda la plataforma. */
    boolean existsByEmail(String email);

    /** Alta de un operador humano de una empresa. El hash de la contrasena llega ya calculado. */
    OperatorUser create(OperatorUser user);

    /** Cambia el estado de la cuenta (alta, suspension o baja). */
    void updateStatus(String userId, UserStatus status);

    /** Actualiza el resultado de identidad junto al estado operativo de la cuenta. */
    void updateIdentity(String userId, OperatorIdentity identity, UserStatus status);

    /**
     * Reemplaza el hash de la contrasena (ya calculado por quien llama) y fija si queda un
     * cambio obligatorio pendiente. {@code passwordResetByAdmin} distingue un reset hecho por un
     * administrador (bypassa la politica de no-reutilizacion en el siguiente cambio) de la
     * contrasena inicial de alta o de un cambio voluntario.
     */
    void updatePassword(String userId, String passwordHash, boolean mustChangePassword,
                        boolean passwordResetByAdmin);
}
