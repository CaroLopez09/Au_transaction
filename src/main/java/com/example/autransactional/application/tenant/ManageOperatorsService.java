package com.example.autransactional.application.tenant;

import com.example.autransactional.application.auth.PasswordService;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.IdentityVerificationStatus;
import com.example.autransactional.domain.tenant.OperatorIdentity;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.email.EmailNotificationService;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Administracion de los operadores de una empresa cliente (G-13 / D5).
 *
 * Hasta ahora los usuarios solo entraban por la semilla de dev o por SQL. Este servicio es el
 * unico camino para darlos de alta desde el portal, y por eso concentra las tres reglas que no
 * pueden quedar en manos del controlador:
 *
 * 1. La empresa sale SIEMPRE de la sesion del administrador, nunca del cuerpo de la peticion.
 * 2. Un ADMIN puede repartir ADMIN o TREASURY_APPROVER dentro de su propia empresa, pero nunca
 *    PLATFORM_OPERATOR: ese rol sigue siendo alta controlada fuera del portal.
 * 3. Nadie se da de baja a si mismo: dejaria a la empresa sin administrador.
 */
@Service
public class ManageOperatorsService {

    /** Roles que un ADMIN puede repartir dentro de su empresa. */
    private static final Set<Role> ASSIGNABLE = Set.of(Role.ADMIN, Role.TREASURY_APPROVER);

    private final OperatorUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrail audit;
    private final PasswordService passwords;
    private final EmailNotificationService email;

    public ManageOperatorsService(OperatorUserRepository users, PasswordEncoder passwordEncoder,
                                  AuditTrail audit, PasswordService passwords, EmailNotificationService email) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.passwords = passwords;
        this.email = email;
    }

    /** Operadores de la empresa del solicitante. La consola de plataforma no entra por aqui. */
    @Transactional(readOnly = true)
    public List<OperatorView> list(AuthenticatedOperator operator) {
        assertTenantOperator(operator);
        return users.findByTenant(operator.tenantId()).stream()
                .map(OperatorView::from)
                .toList();
    }

    @Transactional
    public OperatorView create(AuthenticatedOperator operator, OperatorCommands.CreateOperator command) {
        assertTenantOperator(operator);
        Role role = assignableRole(command.role());
        String email = command.email().trim().toLowerCase(Locale.ROOT);

        // El correo es unico en toda la plataforma (uk_users_email), no por empresa: si se deja
        // llegar al INSERT, MySQL responde con una violacion de constraint y un 500 sin sentido.
        if (users.existsByEmail(email)) {
            throw new DomainException("Ya existe un usuario con ese correo.");
        }

        // La contrasena SIEMPRE la genera el backend: nadie la escribe ni Angular la conoce.
        // No aparece en logs ni en la respuesta HTTP; solo viaja al correo del operador.
        String temporaryPassword = passwords.generateTemporary();

        OperatorUser nuevo = new OperatorUser(
                UUID.randomUUID().toString(),
                operator.tenantId(),
                email,
                passwordEncoder.encode(temporaryPassword),
                command.firstName().trim(),
                command.lastName().trim(),
                role,
                UserStatus.PENDING_IDENTITY,
                null,
                false,
                OperatorIdentity.pendingDocuments(),
                true,
                false);

        OperatorUser creado = users.create(nuevo);
        audit.record(operator, "operator.created", "operator_user", creado.id(), null, "OK",
                "rol=" + role.name());
        this.email.sendAccountCreatedEmail(creado.email(), creado.fullName(), temporaryPassword);
        return OperatorView.from(creado);
    }

    /**
     * Reset administrativo: genera una nueva contrasena temporal, invalida la anterior, marca el
     * cambio obligatorio y la envia por correo. Nunca se devuelve en la respuesta HTTP: la unica
     * forma de conocerla es el correo del operador (regla de seguridad del portal).
     */
    @Transactional
    public void resetPassword(AuthenticatedOperator operator, String userId) {
        assertTenantOperator(operator);

        OperatorUser objetivo = users.findById(userId)
                .orElseThrow(() -> new DomainException("El operador no existe."));
        if (!objetivo.tenantId().equals(operator.tenantId())) {
            throw new DomainException("El operador no existe.");
        }

        String temporaryPassword = passwords.generateTemporary();
        passwords.applyTemporaryPassword(objetivo, temporaryPassword, true);

        audit.record(operator, "operator.password_reset", "operator_user", objetivo.id(), null, "OK",
                "rol=" + objetivo.role().name());
        this.email.sendPasswordResetEmail(objetivo.email(), objetivo.fullName(), temporaryPassword);
    }

    /**
     * Suspende a un operador de la propia empresa. Es SUSPENDED y no DISABLED a proposito:
     * la accion del portal es reversible y no borra la persona, que sigue siendo el actor de
     * los pagos y las aprobaciones que ya firmo.
     */
    @Transactional
    public OperatorView suspend(AuthenticatedOperator operator, String userId) {
        assertTenantOperator(operator);
        if (operator.userId().equals(userId)) {
            throw new DomainException("No puedes desactivar tu propia cuenta.");
        }

        OperatorUser objetivo = users.findById(userId)
                .orElseThrow(() -> new DomainException("El operador no existe."));
        // Mismo mensaje que si no existiera: no revelamos usuarios de otras organizaciones.
        if (!objetivo.tenantId().equals(operator.tenantId())) {
            throw new DomainException("El operador no existe.");
        }
        if (!objetivo.isActive()) {
            throw new DomainException("El operador ya esta desactivado.");
        }

        users.updateStatus(objetivo.id(), UserStatus.SUSPENDED);
        audit.record(operator, "operator.suspended", "operator_user", objetivo.id(), null, "OK",
                "rol=" + objetivo.role().name());

        return OperatorView.from(new OperatorUser(objetivo.id(), objetivo.tenantId(), objetivo.email(),
                objetivo.passwordHash(), objetivo.firstName(), objetivo.lastName(), objetivo.role(),
                UserStatus.SUSPENDED, objetivo.mfaSecret(), objetivo.mfaEnabled()));
    }

    /**
     * Revierte una suspension. La identidad no cambia: si seguia sin verificar (p. ej. rechazada),
     * el proximo intento de login vuelve a pedir el reto biometrico, como con cualquier cuenta
     * pendiente de identidad.
     */
    @Transactional
    public OperatorView reactivate(AuthenticatedOperator operator, String userId) {
        assertTenantOperator(operator);

        OperatorUser objetivo = users.findById(userId)
                .orElseThrow(() -> new DomainException("El operador no existe."));
        if (!objetivo.tenantId().equals(operator.tenantId())) {
            throw new DomainException("El operador no existe.");
        }
        if (objetivo.status() != UserStatus.SUSPENDED) {
            throw new DomainException("Solo se puede reactivar una cuenta desactivada.");
        }

        users.updateStatus(objetivo.id(), UserStatus.ACTIVE);
        audit.record(operator, "operator.reactivated", "operator_user", objetivo.id(), null, "OK",
                "rol=" + objetivo.role().name());

        return OperatorView.from(users.findById(objetivo.id())
                .orElseThrow(() -> new DomainException("El operador no existe.")));
    }

    /**
     * Limpia una identidad rechazada para que la persona pueda volver a subir documentos y rostro.
     * No toca el estado operativo de la cuenta: si estaba desactivada, sigue estandolo hasta que
     * un ADMIN la reactive por separado.
     */
    @Transactional
    public OperatorView relaunchIdentity(AuthenticatedOperator operator, String userId) {
        assertTenantOperator(operator);

        OperatorUser objetivo = users.findById(userId)
                .orElseThrow(() -> new DomainException("El operador no existe."));
        if (!objetivo.tenantId().equals(operator.tenantId())) {
            throw new DomainException("El operador no existe.");
        }
        if (objetivo.identity().status() != IdentityVerificationStatus.REJECTED) {
            throw new DomainException("Solo se puede relanzar la verificacion de una identidad no aprobada.");
        }

        users.updateIdentity(objetivo.id(), OperatorIdentity.pendingDocuments(),
                objetivo.status());
        audit.record(operator, "operator.identity_relaunched", "operator_user", objetivo.id(), null, "OK",
                "rol=" + objetivo.role().name());

        return OperatorView.from(users.findById(objetivo.id())
                .orElseThrow(() -> new DomainException("El operador no existe.")));
    }

    private Role assignableRole(String raw) {
        Role role;
        try {
            role = Role.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException("Rol desconocido: " + raw);
        }
        if (!ASSIGNABLE.contains(role)) {
            throw new DomainException("No puedes asignar el rol " + role.name() + " desde el portal.");
        }
        return role;
    }

    /** Un operador de la plataforma no tiene empresa: sus rutas son las de la consola. */
    private void assertTenantOperator(AuthenticatedOperator operator) {
        if (operator.role().isPlatform() || operator.tenantId().isPlatform()) {
            throw new DomainException("Esta ruta es de una empresa cliente.");
        }
    }
}
