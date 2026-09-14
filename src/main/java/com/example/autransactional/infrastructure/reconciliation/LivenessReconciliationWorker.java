package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.domain.tenant.Ubo;
import com.example.autransactional.domain.tenant.UboRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Cierra los enlaces de prueba de vida vencidos.
 *
 * El enlace que emite Kira vive 7 dias y su resultado real solo llega por el webhook
 * user.liveness_completed. Un enlace vencido que sigue en PENDING deja al portal esperando algo
 * que ya no va a pasar: se marca EXPIRED para que la pantalla ofrezca pedir uno nuevo, que es lo
 * unico que funciona (Kira no prorroga el enlace).
 */
@Component
@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LivenessReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(LivenessReconciliationWorker.class);

    private final UboRepository ubos;

    public LivenessReconciliationWorker(UboRepository ubos) {
        this.ubos = ubos;
    }

    @Scheduled(fixedDelayString = "${bff.reconciliation.liveness-ms:3600000}",
            initialDelayString = "${bff.reconciliation.initial-delay-ms:60000}")
    public void expireStaleLivenessLinks() {
        List<Ubo> vencidos = ubos.findPendingLivenessExpiredBefore(Instant.now());
        if (vencidos.isEmpty()) {
            return;
        }
        for (Ubo ubo : vencidos) {
            try {
                ubo.expireLivenessLink();
                ubos.save(ubo);
            } catch (RuntimeException e) {
                log.error("No se pudo vencer el enlace de liveness de {}: {}", ubo.getId(), e.getMessage());
            }
        }
        log.info("Reconciliacion de liveness: {} enlaces marcados como vencidos.", vencidos.size());
    }
}
