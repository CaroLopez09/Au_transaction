package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraProperties;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ImportSandboxTenantServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
        private final OperatorUserRepository users = mock(OperatorUserRepository.class);
    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final ImportSandboxTenantService service = new ImportSandboxTenantService(tenants, accounts, users,
            mock(org.springframework.security.crypto.password.PasswordEncoder.class), kira,
            new KiraProperties("https://kira.test", "k", "c", "p", "2026-06-01", "w", null,
                    3600, 300, 5000, 30000, "jp_morgan", true), audit);

    @Test
    void importaLaEmpresaYAdoptaCadaCuentaRemota() {
        when(kira.getUser("usr_1")).thenReturn(json("""
                { "id": "usr_1", "status": "VERIFIED", "missing_fields": {}, "eligible_products": [] }
                """));
        when(kira.listVirtualAccounts(eq(java.util.Map.of("user_id", "usr_1")))).thenReturn(json("""
                [{ "id": "va_1", "currency": "USD", "mode": "fiat" }]
                """));
        when(kira.getVirtualAccount("va_1")).thenReturn(json("""
                { "id": "va_1", "status": "ACTIVE", "currency": "USD", "mode": "fiat",
                  "bank_name": "JP Morgan", "account_number": "123", "routing_number": "021" }
                """));
        when(tenants.findByKiraUserId("usr_1")).thenReturn(Optional.empty());
        when(accounts.findByKiraAccountId("va_1")).thenReturn(Optional.empty());
        when(users.findByTenant(any())).thenReturn(java.util.List.of());
        when(users.existsByEmail(any())).thenReturn(false);
        when(users.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.importTenant(platform(),
                command("usr_1", "Juriscop", "900123456-1"));

        assertEquals("usr_1", result.tenant().kiraUserId());
        assertEquals("VERIFIED", result.tenant().status());
        assertEquals(1, result.synchronizedAccounts());
        ArgumentCaptor<OperatorUser> administrator = ArgumentCaptor.forClass(OperatorUser.class);
        verify(users).create(administrator.capture());
        assertEquals("admin@empresa.test", administrator.getValue().email());
        assertEquals(Role.ADMIN, administrator.getValue().role());
        verify(tenants).save(any(Tenant.class));
        verify(accounts).save(any(VirtualAccount.class));
    }

    @Test
    void unAdministradorNoPuedeImportarUnaEmpresaAjena() {
        Tenant tenant = new Tenant(TenantId.of("juriscop"), "Juriscop", "900", "Colombia");
        tenant.linkKiraUser("usr_1");
        when(tenants.findById(TenantId.of("juriscop"))).thenReturn(Optional.of(tenant));
        when(kira.getUser("usr_2")).thenReturn(json("{ " + "\"id\": \"usr_2\" }"));

        assertThrows(DomainException.class, () -> service.importTenant(
                new AuthenticatedOperator("u-1", "admin@juriscop.test", TenantId.of("juriscop"), Role.ADMIN),
                command("usr_2", "Otra", "901")));
    }

    private AuthenticatedOperator platform() {
        return new AuthenticatedOperator("platform-1", "ops@au.test", TenantId.PLATFORM, Role.PLATFORM_OPERATOR);
    }

        private static ImportSandboxTenantService.ImportCommand command(String kiraUserId, String name, String taxId) {
                return new ImportSandboxTenantService.ImportCommand(kiraUserId, name, taxId,
                                new ImportSandboxTenantService.InitialAdministrator("admin@empresa.test", "Admin", "Empresa", "Password123!"));
        }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }
}