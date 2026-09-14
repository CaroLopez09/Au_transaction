package com.example.autransactional.infrastructure.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Los workers son la red de seguridad de los webhooks perdidos: si no se registran como beans,
 * nada avisa y el hueco vuelve sin que nadie lo note.
 */
@SpringBootTest
@TestPropertySource(properties = "bff.reconciliation.enabled=true")
class ReconciliationWorkersEnabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void losCincoWorkersSeRegistranCuandoLaReconciliacionEstaActiva() {
        assertEquals(1, context.getBeansOfType(PayoutReconciliationWorker.class).size());
        assertEquals(1, context.getBeansOfType(QuotationReconciliationWorker.class).size());
        assertEquals(1, context.getBeansOfType(LivenessReconciliationWorker.class).size());
        assertEquals(1, context.getBeansOfType(RfiReconciliationWorker.class).size());
        assertEquals(1, context.getBeansOfType(WebhookReprojectionWorker.class).size());
    }
}
