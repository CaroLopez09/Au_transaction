package com.example.autransactional.application.shared;

import com.example.autransactional.application.account.OpenVirtualAccountService;
import com.example.autransactional.application.account.VirtualAccountCommands;
import com.example.autransactional.application.tenant.OnboardingCommands;
import com.example.autransactional.application.tenant.SubmitOnboardingService;
import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.MissingFields;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraApiException;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * La clave de idempotencia tiene que estar en MySQL ANTES de llamar a Kira y seguir ahi si la
 * llamada falla: si Kira llego a crear el recurso y la respuesta se perdio, el reintento debe
 * viajar con la MISMA clave o se crea una segunda empresa o una segunda cuenta.
 *
 * Estas pruebas son de integracion a proposito: el caso de uso es @Transactional y relanza la
 * excepcion, asi que el rollback borraba el guardado. Con mocks y sin transaccion real ese
 * defecto no se ve.
 */
@SpringBootTest
class IdempotencyKeyPersistenceTest {

    @Autowired
    private SubmitOnboardingService onboarding;

    @Autowired
    private OpenVirtualAccountService accountsService;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private VirtualAccountRepository accounts;

    @MockitoBean
    private KiraApiClient kira;

    private AuthenticatedOperator operador(TenantId tenantId, Role role) {
        return new AuthenticatedOperator(tenantId.value() + ":op", "op@" + tenantId.value() + ".test",
                tenantId, role);
    }

    private Tenant nuevaEmpresa(String id) {
        Tenant tenant = new Tenant(TenantId.of(id), "Empresa " + id, "900-" + id, "Colombia");
        tenants.save(tenant);
        return tenant;
    }

    @Test
    void laClaveDelAltaSobreviveAlFalloDeKira() {
        TenantId tenantId = nuevaEmpresa("idem-alta").getId();
        when(kira.createUser(any(), any()))
                .thenThrow(new KiraApiException(504, null, "timeout hablando con Kira", null));

        assertThrows(KiraApiException.class, () -> onboarding.register(operador(tenantId, Role.COMPLIANCE_INTERNAL),
                new OnboardingCommands.RegisterBusiness("Empresa Idem S.A.S.", "finanzas@idem.co",
                        "sales_of_goods_and_services")));

        String clave = tenants.findById(tenantId).orElseThrow().getOnboardingIdempotencyKey();
        assertNotNull(clave, "el rollback del caso de uso no debe borrar la clave ya reservada");

        // El reintento reutiliza exactamente la misma clave: Kira no crea una segunda empresa.
        assertThrows(KiraApiException.class, () -> onboarding.register(operador(tenantId, Role.COMPLIANCE_INTERNAL),
                new OnboardingCommands.RegisterBusiness("Empresa Idem S.A.S.", "finanzas@idem.co",
                        "sales_of_goods_and_services")));
        assertEquals(clave, tenants.findById(tenantId).orElseThrow().getOnboardingIdempotencyKey());
    }

    @Test
    void laClaveDeAperturaDeCuentaSobreviveAlFalloDeKira() {
        Tenant tenant = nuevaEmpresa("idem-cuenta");
        tenant.linkKiraUser("usr_idem_cuenta");
        tenant.applyRemoteState(TenantStatus.VERIFIED, MissingFields.empty(),
                List.of(new EligibleProduct(EligibleProduct.USA_VIRTUAL_ACCOUNTS, true, List.of(), null)), true);
        tenants.save(tenant);
        TenantId tenantId = tenant.getId();
        when(kira.createVirtualAccount(any(), any()))
                .thenThrow(new KiraApiException(504, null, "timeout hablando con Kira", null));

        assertThrows(KiraApiException.class, () -> accountsService.open(operador(tenantId, Role.TREASURY_MAKER),
                new VirtualAccountCommands.OpenAccount("Operativa", "fiat", "USD")));

        List<VirtualAccount> guardadas = accounts.findByTenant(tenantId);
        assertEquals(1, guardadas.size(), "la cuenta reservada debe quedar en base de datos");
        assertNotNull(guardadas.getFirst().getOpeningIdempotencyKey(),
                "el rollback del caso de uso no debe borrar la clave de apertura");
    }

}
