package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.domain.treasury.PayoutStatus;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Pagos en vuelo: pregunta por el recurso, que es la autoridad final.
 *
 * Kira entrega cada webhook una sola vez y sin reintentos: si el BFF estaba caido o la proyeccion
 * fallo, ese cambio de estado no vuelve. Aqui se recuperan los pagos que Kira ya conoce y siguen
 * sin estado terminal (CREATED, PENDING, PROCESSING, KYT_PENDING, IN_REVIEW y UNKNOWN).
 *
 * Cada pago se guarda por separado: un fallo de red en uno no debe tumbar el lote ni dejar a
 * medias los anteriores.
 */
@Component
@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PayoutReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconciliationWorker.class);

    private final PayoutRepository payouts;
    private final KiraApiClient kira;
    private final int batchSize;

    public PayoutReconciliationWorker(PayoutRepository payouts, KiraApiClient kira,
                                      @org.springframework.beans.factory.annotation.Value(
                                              "${bff.reconciliation.payout-batch:50}") int batchSize) {
        this.payouts = payouts;
        this.kira = kira;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${bff.reconciliation.payouts-ms:600000}",
            initialDelayString = "${bff.reconciliation.initial-delay-ms:60000}")
    public void reconcile() {
        List<Payout> enVuelo = payouts.findInFlight(batchSize);
        if (enVuelo.isEmpty()) {
            return;
        }
        int actualizados = 0;
        for (Payout payout : enVuelo) {
            try {
                if (refresh(payout)) {
                    actualizados++;
                }
            } catch (KiraNotConfiguredException e) {
                // Sin credenciales no hay nada que reconciliar: se corta el lote sin ruido por pago.
                log.warn("Reconciliacion de pagos omitida: {}", e.getMessage());
                return;
            } catch (RuntimeException e) {
                log.error("No se pudo reconciliar el pago {}: {}", payout.getId(), e.getMessage());
            }
        }
        log.info("Reconciliacion de pagos: {} revisados, {} actualizados.", enVuelo.size(), actualizados);
    }

    /** Devuelve true si el estado o el comprobante cambiaron. */
    private boolean refresh(Payout payout) {
        JsonNode response = kira.getPayout(payout.getKiraPayoutId());
        JsonNode body = response != null && response.has("data") ? response.get("data") : response;
        if (body == null) {
            return false;
        }
        PayoutStatus antes = payout.getStatus();
        String comprobanteAntes = payout.getReferenceNumber();

        payout.applyRemoteStatus(PayoutStatus.fromWire(text(body, "status")), text(body, "error_code"));
        payout.describeRemote(text(body, "reference_number"), text(body, "payment_method"));
        payouts.save(payout);

        boolean cambio = antes != payout.getStatus()
                || (comprobanteAntes == null && payout.getReferenceNumber() != null);
        if (cambio) {
            log.info("Pago {} reconciliado: {} -> {}.", payout.getId(), antes, payout.getStatus());
        }
        return cambio;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
