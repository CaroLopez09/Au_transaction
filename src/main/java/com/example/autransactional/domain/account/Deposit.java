package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Deposito entrante sobre una cuenta virtual.
 *
 * Se guardan los tres importes por separado porque son tres hechos distintos:
 * lo que envio el ordenante (bruto), lo que cobro el banco (comision) y lo que
 * quedo disponible (neto). Derivar uno de los otros pierde el desglose contable.
 */
@Getter
public class Deposit {

    private final String id;
    private final TenantId tenantId;
    private final String virtualAccountId;
    private final String currency;
    private final Instant createdAt;

    private String kiraDepositId;
    private BigDecimal grossAmount;
    private BigDecimal feeAmount;
    private BigDecimal netAmount;
    private String senderName;
    private String senderAccount;
    private Rail rail;
    private DepositStatus status;
    /** Deposito de verificacion de cuenta, no un ingreso real del cliente. */
    private boolean microdeposit;
    private Instant updatedAt;

    public Deposit(String id, TenantId tenantId, String virtualAccountId,
                   BigDecimal grossAmount, BigDecimal feeAmount, String currency) {
        if (grossAmount == null || grossAmount.signum() <= 0) {
            throw new DomainException("El importe bruto del deposito debe ser mayor que cero.");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.virtualAccountId = virtualAccountId;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount == null ? BigDecimal.ZERO : feeAmount;
        this.netAmount = this.grossAmount.subtract(this.feeAmount);
        this.currency = currency;
        this.status = DepositStatus.COMPLETED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Deposit rehydrate(String id, TenantId tenantId, String virtualAccountId,
                                    String kiraDepositId, BigDecimal grossAmount, BigDecimal feeAmount,
                                    BigDecimal netAmount, String currency, String senderName,
                                    String senderAccount, Rail rail, DepositStatus status,
                                    boolean microdeposit, Instant createdAt, Instant updatedAt) {
        Deposit d = new Deposit(id, tenantId, virtualAccountId, grossAmount, feeAmount, currency);
        d.kiraDepositId = kiraDepositId;
        d.netAmount = netAmount == null ? d.netAmount : netAmount;
        d.senderName = senderName;
        d.senderAccount = senderAccount;
        d.rail = rail;
        d.status = status == null ? DepositStatus.COMPLETED : status;
        d.microdeposit = microdeposit;
        d.updatedAt = updatedAt;
        return d;
    }

    public void markAsMicrodeposit() {
        this.microdeposit = true;
        touch();
    }

    /**
     * Aplica el estado que trae un evento.
     *
     * No retrocede desde un estado terminal: los eventos llegan una sola vez y sin orden
     * garantizado, asi que un 'in_transit' que llega tarde no puede resucitar un deposito
     * ya devuelto.
     */
    public void applyRemoteStatus(DepositStatus incoming) {
        if (incoming == null || (status.isTerminal() && !incoming.isTerminal())) {
            return;
        }
        this.status = incoming;
        touch();
    }

    /** Corrige los importes con lo que traiga un evento posterior mas completo. */
    public void restate(BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal netAmount) {
        if (grossAmount != null && grossAmount.signum() > 0) {
            this.grossAmount = grossAmount;
        }
        if (feeAmount != null) {
            this.feeAmount = feeAmount;
        }
        this.netAmount = netAmount != null ? netAmount : this.grossAmount.subtract(this.feeAmount);
        touch();
    }

    public boolean creditsBalance() {
        return status.creditsBalance() && !microdeposit;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public void describeSender(String senderName, String senderAccount, Rail rail) {
        if (senderName != null && !senderName.isBlank()) {
            this.senderName = senderName;
        }
        if (senderAccount != null && !senderAccount.isBlank()) {
            this.senderAccount = senderAccount;
        }
        if (rail != null) {
            this.rail = rail;
        }
        touch();
    }

    public void linkKiraDeposit(String kiraDepositId) {
        this.kiraDepositId = kiraDepositId;
        touch();
    }

    public Money net() {
        return Money.of(netAmount, currency);
    }

    public Money gross() {
        return Money.of(grossAmount, currency);
    }
}
