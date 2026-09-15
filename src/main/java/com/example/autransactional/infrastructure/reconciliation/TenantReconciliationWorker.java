package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.application.tenant.SubmitOnboardingService;
import com.example.autransactional.domain.tenant.EligibleProduct;
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
 * Estado KYB de las empresas que aun no pueden operar.
 *
 * user.status_changed es la unica senal de cada transicion, y Kira deja de reintentar una entrega
 * a los ~80 minutos. Si se pierde, una empresa verificada seguiria viendose en revision hasta que
 * alguien pulse "Actualizar". Las empresas ya verificadas y listas no se consultan.
 */
@Component
@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TenantReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(TenantReconciliationWorker.class);

    private final TenantRepository tenants;
    private final SubmitOnboardingService onboarding;

    public TenantReconciliationWorker(TenantRepository tenants, SubmitOnboardingService onboarding) {
        this.tenants = tenants;
        this.onboarding = onboarding;
    }

    @Scheduled(fixedDelayString = "${bff.reconciliation.tenants-ms:1800000}",
            initialDelayString = "${bff.reconciliation.initial-delay-ms:60000}")
    public void reconcile() {
        List<Tenant> pendientes = tenants.findAll().stream()
                .filter(Tenant::isRegisteredInKira)
                .filter(t -> !(t.isVerified() && t.isReadyFor(EligibleProduct.USA_VIRTUAL_ACCOUNTS)))
                .toList();
        if (pendientes.isEmpty()) {
            return;
        }
        int cambiadas = 0;
        for (Tenant tenant : pendientes) {
            try {
                if (onboarding.reconcile(tenant.getId())) {
                    cambiadas++;
                }
            } catch (KiraNotConfiguredException e) {
                log.warn("Reconciliacion de empresas omitida: {}", e.getMessage());
                return;
            } catch (RuntimeException e) {
                log.error("No se pudo reconciliar la empresa {}: {}", tenant.getId().value(), e.getMessage());
            }
        }
        log.info("Reconciliacion de empresas: {} revisadas, {} con cambios.", pendientes.size(), cambiadas);
    }
}
