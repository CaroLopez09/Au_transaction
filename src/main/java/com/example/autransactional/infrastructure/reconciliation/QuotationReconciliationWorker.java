package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.domain.treasury.Quotation;
import com.example.autransactional.domain.treasury.QuotationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Cierra las cotizaciones cuyo TTL de 15 minutos ya paso.
 *
 * No llama a Kira: el vencimiento es local y deterministico. Sirve para que la bandeja no muestre
 * como ACTIVE un precio que ya no se puede redimir; el pago, por su parte, vuelve a comprobar el
 * vencimiento antes de enviar nada.
 */
@Component
@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QuotationReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(QuotationReconciliationWorker.class);

    private final QuotationRepository quotations;

    public QuotationReconciliationWorker(QuotationRepository quotations) {
        this.quotations = quotations;
    }

    @Scheduled(fixedDelayString = "${bff.reconciliation.quotations-ms:300000}",
            initialDelayString = "${bff.reconciliation.initial-delay-ms:60000}")
    public void expireStaleQuotations() {
        List<Quotation> vencidas = quotations.findActiveExpiredBefore(Instant.now());
        if (vencidas.isEmpty()) {
            return;
        }
        for (Quotation quotation : vencidas) {
            try {
                quotation.expire();
                quotations.save(quotation);
            } catch (RuntimeException e) {
                log.error("No se pudo marcar como vencida la cotizacion {}: {}",
                        quotation.getId(), e.getMessage());
            }
        }
        log.info("Reconciliacion de cotizaciones: {} marcadas como vencidas.", vencidas.size());
    }
}
