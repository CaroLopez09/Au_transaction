package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.application.compliance.AnswerRfiService;
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
 * Trae de Kira los RFIs de cada empresa registrada.
 *
 * La familia rfi.* exige suscripcion explicita en Kira y, como todo webhook, se entrega una sola
 * vez. Un RFI que no llega es un pago o un deposito detenido que nadie ve hasta que vence, y el
 * plazo (due_at, unas dos semanas) no se prorroga: por eso esta es la red de seguridad que mas
 * importa de las cuatro.
 */
@Component
@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RfiReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(RfiReconciliationWorker.class);

    private final TenantRepository tenants;
    private final AnswerRfiService rfis;

    public RfiReconciliationWorker(TenantRepository tenants, AnswerRfiService rfis) {
        this.tenants = tenants;
        this.rfis = rfis;
    }

    @Scheduled(fixedDelayString = "${bff.reconciliation.rfis-ms:900000}",
            initialDelayString = "${bff.reconciliation.initial-delay-ms:60000}")
    public void syncOpenRfis() {
        List<Tenant> registradas = tenants.findAll().stream()
                .filter(Tenant::isRegisteredInKira)
                .toList();
        if (registradas.isEmpty()) {
            return;
        }
        int total = 0;
        for (Tenant tenant : registradas) {
            try {
                total += rfis.syncForTenant(tenant.getId());
            } catch (KiraNotConfiguredException e) {
                // Sin credenciales no hay nada que sincronizar: se corta sin repetir el aviso por empresa.
                log.warn("Sincronizacion de RFIs omitida: {}", e.getMessage());
                return;
            } catch (RuntimeException e) {
                log.error("No se pudieron sincronizar los RFIs de {}: {}", tenant.getId().value(), e.getMessage());
            }
        }
        log.info("Reconciliacion de RFIs: {} empresas revisadas, {} RFIs asentados.", registradas.size(), total);
    }
}
