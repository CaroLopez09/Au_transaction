package com.example.autransactional.infrastructure.bootstrap;

import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.infrastructure.persistence.OperatorUserJpaRepository;
import com.example.autransactional.infrastructure.persistence.TenantJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // El perfil dev apunta a MySQL; aqui basta con la base en memoria de las pruebas.
        "spring.datasource.url=jdbc:h2:mem:seeder;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "bff.dev.seed=true",
        "bff.dev.seed-password=Prueba123!"
})
class DevDataSeederTest {

    @Autowired
    private TenantJpaRepository tenants;

    @Autowired
    private OperatorUserJpaRepository operators;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DevDataSeeder seeder;

    @Test
    void creaLasTresOrganizacionesConUnOperadorPorRolYUnOperadorDePlataforma() {
        assertEquals(3, tenants.count());
        assertTrue(tenants.findById("juriscop").isPresent());
        long rolesDeEmpresa = java.util.Arrays.stream(Role.values()).filter(r -> !r.isPlatform()).count();
        assertEquals(3L * rolesDeEmpresa + 1, operators.count());

        var plataforma = operators.findByEmailIgnoreCase("operaciones@au.test").orElseThrow();
        assertNull(plataforma.getTenantId());
        assertEquals(Role.PLATFORM_OPERATOR, Role.fromDbName(plataforma.getRole().getName()));
    }

    @Test
    void laContrasenaQuedaCifradaNoEnClaro() {
        var admin = operators.findByEmailIgnoreCase("admin@juriscop.test").orElseThrow();

        assertNotEquals("Prueba123!", admin.getPasswordHash());
        assertTrue(passwordEncoder.matches("Prueba123!", admin.getPasswordHash()));
    }

    @Test
    void adminYApproverSonOperadoresDistintosDelMismoTenant() {
        var admin = operators.findByEmailIgnoreCase("admin@juriscop.test").orElseThrow();
        var approver = operators.findByEmailIgnoreCase("treasury.approver@juriscop.test").orElseThrow();

        assertEquals(Role.ADMIN, Role.fromDbName(admin.getRole().getName()));
        assertEquals(Role.TREASURY_APPROVER, Role.fromDbName(approver.getRole().getName()));
        // El nombre tecnico es el del esquema v2, no el de la constante Java.
        assertEquals("admin", admin.getRole().getName());
        assertEquals(admin.getTenantId(), approver.getTenantId());
        assertNotEquals(admin.getId(), approver.getId());
    }

    @Test
    void volverARegarNoDuplicaNada() {
        long tenantsAntes = tenants.count();
        long operadoresAntes = operators.count();

        seeder.run(new org.springframework.boot.DefaultApplicationArguments());

        assertEquals(tenantsAntes, tenants.count());
        assertEquals(operadoresAntes, operators.count());
    }
}
