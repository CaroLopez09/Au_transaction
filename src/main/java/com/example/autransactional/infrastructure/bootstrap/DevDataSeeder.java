package com.example.autransactional.infrastructure.bootstrap;

import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.infrastructure.persistence.OperatorUserEntity;
import com.example.autransactional.infrastructure.persistence.OperatorUserJpaRepository;
import com.example.autransactional.infrastructure.persistence.RoleEntity;
import com.example.autransactional.infrastructure.persistence.RoleJpaRepository;
import com.example.autransactional.infrastructure.persistence.TenantEntity;
import com.example.autransactional.infrastructure.persistence.TenantJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Datos de arranque para desarrollo local.
 *
 * Solo se activa con el perfil dev y bff.dev.seed=true, nunca en cert ni en prod.
 * Es idempotente: si la organizacion, el rol o el correo ya existen, no los toca.
 *
 * Crea un operador por rol y por organizacion para poder probar de verdad el maker-checker:
 * hacen falta dos personas distintas para que un pago salga hacia Kira.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "bff.dev", name = "seed", havingValue = "true")
@EnableConfigurationProperties(DevSeedProperties.class)
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private record SeedTenant(String id, String name, String taxId) {
    }

    private static final List<SeedTenant> TENANTS = List.of(
            new SeedTenant("juriscop", "Juriscop", "900123456-1"),
            new SeedTenant("bankvision", "Bankvision", "900234567-2"),
            new SeedTenant("au-colombia", "AU Colombia", "900345678-3"));

    private final TenantJpaRepository tenants;
    private final RoleJpaRepository roles;
    private final OperatorUserJpaRepository operators;
    private final PasswordEncoder passwordEncoder;
    private final DevSeedProperties properties;

    public DevDataSeeder(TenantJpaRepository tenants, RoleJpaRepository roles,
                         OperatorUserJpaRepository operators, PasswordEncoder passwordEncoder,
                         DevSeedProperties properties) {
        this.tenants = tenants;
        this.roles = roles;
        this.operators = operators;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(org.springframework.boot.ApplicationArguments args) {
        // El hash de bcrypt es caro: se calcula una vez y se reutiliza en toda la semilla.
        String passwordHash = passwordEncoder.encode(properties.seedPassword());
        int nuevosTenants = 0;
        int nuevosOperadores = 0;

        for (Role role : Role.values()) {
            ensureRole(role);
        }

        for (SeedTenant seed : TENANTS) {
            if (tenants.findById(seed.id()).isEmpty()) {
                TenantEntity tenant = new TenantEntity();
                tenant.setId(seed.id());
                tenant.setName(seed.name());
                tenant.setTaxId(seed.taxId());
                tenant.setJurisdiction("Colombia");
                // CREATED y sin kira_user_id: la empresa aun no existe en Kira. Marcarla VERIFIED
                // aqui hacia creer al portal que el KYB estaba aprobado cuando no habia nada detras.
                tenant.setStatus(TenantStatus.CREATED);
                tenants.save(tenant);
                nuevosTenants++;
            }

            for (Role role : Role.values()) {
                String email = role.name().toLowerCase().replace('_', '.') + "@" + seed.id() + ".test";
                if (operators.findByEmailIgnoreCase(email).isPresent()) {
                    continue;
                }
                OperatorUserEntity user = new OperatorUserEntity();
                user.setId(seed.id() + ":" + role.name().toLowerCase());
                user.setTenantId(seed.id());
                user.setEmail(email);
                user.setPasswordHash(passwordHash);
                user.setFirstName(nombreDe(role));
                user.setLastName(seed.name());
                user.setRole(ensureRole(role));
                user.setStatus(UserStatus.ACTIVE);
                operators.save(user);
                nuevosOperadores++;
            }
        }

        if (nuevosTenants > 0 || nuevosOperadores > 0) {
            log.warn("Semilla de desarrollo aplicada: {} organizaciones y {} operadores nuevos. "
                            + "Todos con la contrasena '{}'. Nunca actives bff.dev.seed fuera de local.",
                    nuevosTenants, nuevosOperadores, properties.seedPassword());
            log.info("Ejemplo de acceso: treasury.maker@juriscop.test / treasury.approver@juriscop.test");
        } else {
            log.info("Semilla de desarrollo: sin cambios, los datos ya existian.");
        }
    }

    /** El catalogo `roles` es la FK de `users`: sin fila no hay usuario que insertar. */
    private RoleEntity ensureRole(Role role) {
        return roles.findByName(role.dbName()).orElseGet(() -> {
            RoleEntity entity = new RoleEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setName(role.dbName());
            entity.setDescription(role.description());
            entity.setScope(role.scope());
            return roles.save(entity);
        });
    }

    private static String nombreDe(Role role) {
        return switch (role) {
            case ADMIN -> "Admin";
            case TREASURY_MAKER -> "Operador";
            case TREASURY_APPROVER -> "Tesorero";
            case COMPLIANCE_INTERNAL -> "Cumplimiento";
            case READ_ONLY -> "Consulta";
        };
    }
}
