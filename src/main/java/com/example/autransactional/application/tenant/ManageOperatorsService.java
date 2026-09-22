package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.infrastructure.audit.AuditTrail;
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
 * 2. Un ADMIN no puede fabricar otro ADMIN ni un PLATFORM_OPERATOR: escalar privilegios desde
 *    el portal convertiria el RBAC en decorativo. Esos dos siguen siendo alta controlada.
 * 3. Nadie se da de baja a si mismo: dejaria a la empresa sin administrador.
 */
@Service
public class ManageOperatorsService {

    /** Roles que un ADMIN puede repartir dentro de su empresa. */
    private static final Set<Role> ASSIGNABLE = Set.of(
            Role.TREASURY_MAKER, Role.TREASURY_APPROVER, Role.COMPLIANCE_INTERNAL, Role.READ_ONLY);

    private final OperatorUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrail audit;

    public ManageOperatorsService(OperatorUserRepository users, PasswordEncoder passwordEncoder,
                                  AuditTrail audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
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

        OperatorUser nuevo = new OperatorUser(
                UUID.randomUUID().toString(),
                operator.tenantId(),
                email,
                passwordEncoder.encode(command.password()),
                command.firstName().trim(),
                command.lastName().trim(),
                role,
                UserStatus.PENDING_IDENTITY,
                null,
                false);

        OperatorUser creado = users.create(nuevo);
        audit.record(operator, "operator.created", "operator_user", creado.id(), null, "OK",
                "rol=" + role.name());
        return OperatorView.from(creado);
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
