package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PayoutTest {

    private Payout newPayout(String makerId) {
        return new Payout("p-1", TenantId.of("t-1"), "kira-user", "va-1", "rec-1",
                Money.of(new BigDecimal("100.00"), "USD"), FeeBreakdown.standard(),
                IdempotencyKey.newKey(), makerId);
    }

    @Test
    void elCreadorNoPuedeAprobarSuPropioPago() {
        Payout payout = newPayout("maker-1");

        DomainException e = assertThrows(DomainException.class,
                () -> payout.approve("maker-1", Instant.now()));

        assertTrue(e.getMessage().contains("no puede autorizar"));
        assertEquals(PayoutApprovalState.PENDING_APPROVAL, payout.getApprovalState());
    }

    @Test
    void unSegundoOperadorSiPuedeAprobar() {
        Payout payout = newPayout("maker-1");

        payout.approve("approver-2", Instant.now());

        assertEquals(PayoutApprovalState.APPROVED, payout.getApprovalState());
        assertEquals("approver-2", payout.getApproverUserId());
    }

    @Test
    void noSePuedeAprobarDosVeces() {
        Payout payout = newPayout("maker-1");
        payout.approve("approver-2", Instant.now());

        assertThrows(DomainException.class, () -> payout.approve("approver-3", Instant.now()));
    }

    @Test
    void unaCotizacionVencidaBloqueaLaAprobacion() {
        Payout payout = newPayout("maker-1");
        Instant now = Instant.now();
        payout.attachQuotation("q-1", now.minusSeconds(1));

        DomainException e = assertThrows(DomainException.class, () -> payout.approve("approver-2", now));
        assertTrue(e.getMessage().contains("cotizacion"));
    }

    @Test
    void noSeEnviaAKiraSinAprobacionInterna() {
        Payout payout = newPayout("maker-1");

        assertThrows(DomainException.class, () -> payout.markAsSubmitted("kira-1", "created"));
    }

    @Test
    void elEstadoEnMinusculasDelCreateSeNormaliza() {
        Payout payout = newPayout("maker-1");
        payout.approve("approver-2", Instant.now());

        payout.markAsSubmitted("kira-1", "created");

        assertEquals(PayoutStatus.CREATED, payout.getStatus());
        assertEquals(PayoutApprovalState.SUBMITTED, payout.getApprovalState());
    }

    @Test
    void unEventoTardioNoRevierteUnEstadoTerminal() {
        Payout payout = newPayout("maker-1");
        payout.approve("approver-2", Instant.now());
        payout.markAsSubmitted("kira-1", "created");
        payout.applyRemoteStatus(PayoutStatus.COMPLETED, null);

        payout.applyRemoteStatus(PayoutStatus.PROCESSING, null);

        assertEquals(PayoutStatus.COMPLETED, payout.getStatus());
    }

    @Test
    void elDesgloseComisionalPorDefectoSonQuinceMasQuince() {
        Payout payout = newPayout("maker-1");

        assertEquals(0, new BigDecimal("15.0000").compareTo(payout.getFees().kiraFee()));
        assertEquals(0, new BigDecimal("15.0000").compareTo(payout.getFees().platformFee()));
        assertEquals(0, new BigDecimal("30.0000").compareTo(payout.getFees().totalFee()));
        // Lo que se debita de la cuenta virtual es el importe enviado mas el cobro total.
        assertEquals(0, new BigDecimal("130.0000").compareTo(payout.totalDebit().amount()));
    }

    @Test
    void elMontoDebeSerPositivo() {
        assertThrows(DomainException.class, () -> new Payout("p", TenantId.of("t"), null, "va", "rec",
                Money.zero("USD"), FeeBreakdown.standard(), IdempotencyKey.newKey(), "maker"));
    }
}
