package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;

import java.util.Locale;

/**
 * RBAC B2B. El nombre tecnico (dbName) es el que vive en la tabla `roles` y el que
 * lee el negocio; la constante es la que usan @PreAuthorize y el JWT.
 *
 * La segregacion de funciones exige separar quien prepara un pago (TREASURY_MAKER)
 * de quien lo autoriza (TREASURY_APPROVER): la API de Kira no ofrece maker-checker
 * para integradores, asi que el control es del BFF.
 */
public enum Role {

    ADMIN("admin", RoleScope.TENANT,
            "Administrador General de la Empresa Cliente"),
    TREASURY_MAKER("tesoreria_maker", RoleScope.TENANT,
            "Operador: Registra borradores, destinatarios y cotiza transferencias"),
    TREASURY_APPROVER("tesoreria_approver", RoleScope.TENANT,
            "Tesorero: Aprueba y autoriza la ejecucion de pagos (Maker-Checker)"),
    COMPLIANCE_INTERNAL("compliance_internal", RoleScope.TENANT,
            "Oficial de Cumplimiento: Revisa Ficha 360, UBOs, Liveness y responde RFIs"),
    READ_ONLY("read_only", RoleScope.TENANT,
            "Solo lectura de saldos, cuentas y movimientos"),
    PLATFORM_OPERATOR("platform_operator", RoleScope.SYSTEM,
            "Operaciones y cumplimiento AU: consola multiempresa de solo lectura");

    private final String dbName;
    private final RoleScope scope;
    private final String description;

    Role(String dbName, RoleScope scope, String description) {
        this.dbName = dbName;
        this.scope = scope;
        this.description = description;
    }

    public String dbName() {
        return dbName;
    }

    public RoleScope scope() {
        return scope;
    }

    public String description() {
        return description;
    }

    public static Role fromDbName(String raw) {
        if (raw != null) {
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (Role role : values()) {
                if (role.dbName.equals(normalized)) {
                    return role;
                }
            }
        }
        throw new DomainException("Rol desconocido: " + raw);
    }

    public boolean canCreatePayout() {
        return this == TREASURY_MAKER || this == ADMIN;
    }

    public boolean canApprovePayout() {
        return this == TREASURY_APPROVER || this == ADMIN;
    }

    public boolean isPlatform() {
        return scope == RoleScope.SYSTEM;
    }

    /** Ficha 360, UBOs, liveness y RFIs. */
    public boolean canManageCompliance() {
        return this == COMPLIANCE_INTERNAL || this == ADMIN;
    }

    /**
     * Borrar un documento de RFI es destructivo e irreversible del lado de Kira: se separa
     * de canManageCompliance() para poder segregarlo por rol (guia de arquitectura §2.5).
     */
    public boolean canDeleteRfiDocuments() {
        return this == ADMIN;
    }
}
