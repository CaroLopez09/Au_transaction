package com.example.autransactional.infrastructure.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El interruptor tiene que apagarlos de verdad: las pruebas corren con
 * bff.reconciliation.enabled=false para que ningun worker toque la base ni llame a Kira.
 */
@SpringBootTest
class ReconciliationWorkersDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void conElInterruptorApagadoNoSeRegistraNinguno() {
        assertTrue(context.getBeansOfType(PayoutReconciliationWorker.class).isEmpty());
        assertTrue(context.getBeansOfType(QuotationReconciliationWorker.class).isEmpty());
        assertTrue(context.getBeansOfType(LivenessReconciliationWorker.class).isEmpty());
        assertTrue(context.getBeansOfType(RfiReconciliationWorker.class).isEmpty());
        assertTrue(context.getBeansOfType(WebhookReprojectionWorker.class).isEmpty());
    }
}
