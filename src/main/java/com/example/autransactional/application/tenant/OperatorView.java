package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.tenant.OperatorUser;

/**
 * Operador tal como lo ve el portal. Nunca lleva el hash de la contrasena ni el secreto TOTP:
 * solo si el segundo factor esta activo, que es lo que el administrador necesita saber.
 */
public record OperatorView(
        String id,
        String email,
        String firstName,
        String lastName,
        String fullName,
        String role,
        String roleDescription,
        String status,
        boolean active,
        boolean mfaEnabled) {

    public static OperatorView from(OperatorUser user) {
        return new OperatorView(
                user.id(),
                user.email(),
                user.firstName(),
                user.lastName(),
                user.fullName(),
                user.role().name(),
                user.role().description(),
                user.status().name(),
                user.isActive(),
                user.mfaEnabled());
    }
}
