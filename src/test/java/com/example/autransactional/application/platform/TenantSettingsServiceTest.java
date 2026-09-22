package com.example.autransactional.application.platform;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantSettingsServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final TenantSettingsService service = new TenantSettingsService(tenants, audit);

    private final AuthenticatedOperator platformOperator =
            new AuthenticatedOperator("u-1", "ops@au.test", TENANT, Role.PLATFORM_OPERATOR);

    @BeforeEach
    void setUp() {
        Tenant empresa = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        when(tenants.findById(TENANT)).thenReturn(Optional.of(empresa));
    }

    @Test
    void unTenantNuevoTieneTodoHabilitadoPorDefecto() {
        TenantSettingsService.TenantSettingsView view = service.get(platformOperator, TENANT.value());

        assertEquals(Set.of("ACH", "WALLET", "WIRE"), Set.copyOf(view.enabledRails()));
        assertEquals(Set.of("COPM", "USDC", "USDT"), Set.copyOf(view.enabledTokens()));
        assertEquals(Set.of("LIVENESS", "RFIS"), Set.copyOf(view.enabledFeatures()));
    }

    @Test
    void seActualizanLosRielesYTokensHabilitados() {
        TenantSettingsService.TenantSettingsView view = service.update(platformOperator, TENANT.value(),
                Set.of("WIRE"), Set.of("USDC"), Set.of("RFIS"));

        assertEquals(Set.of("WIRE"), Set.copyOf(view.enabledRails()));
        assertEquals(Set.of("USDC"), Set.copyOf(view.enabledTokens()));
        assertEquals(Set.of("RFIS"), Set.copyOf(view.enabledFeatures()));
        // Se persiste: una segunda lectura debe ver lo mismo, no los defaults.
        assertEquals(Set.of("WIRE"), Set.copyOf(service.get(platformOperator, TENANT.value()).enabledRails()));
    }

    @Test
    void unTenantInexistenteFalla() {
        when(tenants.findById(TenantId.of("no-existe"))).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> service.get(platformOperator, "no-existe"));
    }
}
