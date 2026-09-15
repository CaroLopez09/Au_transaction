package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.application.account.OpenVirtualAccountService;
import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.account.VirtualAccountStatus;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.kira.KiraNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Estado de las cuentas virtuales abiertas en Kira.
 *
 * virtual_account.activated es el unico evento de la cuenta: failed, deactivated y frozen no
 * tienen webhook, asi que solo se descubren consultando el recurso. Una cuenta congelada que se
 * sigue mostrando operativa es un pago que falla sin explicacion.
 */
@Component
@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VirtualAccountReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(VirtualAccountReconciliationWorker.class);

    private final TenantRepository tenants;
    private final VirtualAccountRepository accounts;
    private final OpenVirtualAccountService service;

    public VirtualAccountReconciliationWorker(TenantRepository tenants, VirtualAccountRepository accounts,
                                              OpenVirtualAccountService service) {
        this.tenants = tenants;
        this.accounts = accounts;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${bff.reconciliation.accounts-ms:3600000}",
            initialDelayString = "${bff.reconciliation.initial-delay-ms:60000}")
    public void reconcile() {
        List<VirtualAccount> abiertas = tenants.findAll().stream()
                .filter(Tenant::isRegisteredInKira)
                .flatMap(t -> accounts.findByTenant(t.getId()).stream())
                .filter(VirtualAccount::isOpenInKira)
                // Una cuenta desactivada es final: no hay nada que recuperar.
                .filter(a -> a.getStatus() != VirtualAccountStatus.INACTIVE)
                .toList();
        if (abiertas.isEmpty()) {
            return;
        }
        int cambiadas = 0;
        for (VirtualAccount account : abiertas) {
            try {
                if (service.reconcile(account)) {
                    cambiadas++;
                }
            } catch (KiraNotConfiguredException e) {
                log.warn("Reconciliacion de cuentas omitida: {}", e.getMessage());
                return;
            } catch (RuntimeException e) {
                log.error("No se pudo reconciliar la cuenta {}: {}", account.getId(), e.getMessage());
            }
        }
        log.info("Reconciliacion de cuentas: {} revisadas, {} con cambios.", abiertas.size(), cambiadas);
    }
}
