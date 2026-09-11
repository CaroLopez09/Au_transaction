package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Agregado Payout. Entidad de dominio pura: sin anotaciones de JPA ni dependencias de framework.
 * Concentra el control interno (maker-checker) que la API de Kira no ofrece a los integradores.
 */
@Getter
public class Payout {

    private final String id;
    private final TenantId tenantId;
    private final String kiraUserId;
    private final String virtualAccountId;
    private final String recipientId;
    private final Money amount;
    private FeeBreakdown fees;
    private final IdempotencyKey idempotencyKey;
    private final String makerUserId;
    private final Instant createdAt;

    private String quotationId;
    private Instant quotationExpiresAt;
    private PayoutApprovalState approvalState;
    private PayoutStatus status;
    private String approverUserId;
    private String rejectionReason;
    private String kiraPayoutId;
    private String errorCode;
    /** IMAD / ACH trace / UETR: el comprobante que el cliente final reclama. */
    private String referenceNumber;
    private String paymentMethod;
    private Instant updatedAt;

    public Payout(String id, TenantId tenantId, String kiraUserId, String virtualAccountId,
                  String recipientId, Money amount, FeeBreakdown fees,
                  IdempotencyKey idempotencyKey, String makerUserId) {
        if (amount == null || !amount.isPositive()) {
            throw new DomainException("El monto del pago debe ser mayor que cero.");
        }
        if (makerUserId == null || makerUserId.isBlank()) {
            throw new DomainException("Todo pago debe registrar quien lo crea.");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.kiraUserId = kiraUserId;
        this.virtualAccountId = virtualAccountId;
        this.recipientId = recipientId;
        this.amount = amount;
        this.fees = fees == null ? FeeBreakdown.standard() : fees;
        this.idempotencyKey = idempotencyKey;
        this.makerUserId = makerUserId;
        this.approvalState = PayoutApprovalState.PENDING_APPROVAL;
        this.status = PayoutStatus.NOT_SUBMITTED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Rehidratacion desde persistencia. */
    public static Payout rehydrate(String id, TenantId tenantId, String kiraUserId, String virtualAccountId,
                                   String recipientId, Money amount, FeeBreakdown fees,
                                   IdempotencyKey idempotencyKey, String makerUserId, Instant createdAt,
                                   String quotationId, Instant quotationExpiresAt,
                                   PayoutApprovalState approvalState, PayoutStatus status,
                                   String approverUserId, String rejectionReason, String kiraPayoutId,
                                   String errorCode, String referenceNumber, String paymentMethod,
                                   Instant updatedAt) {
        Payout p = new Payout(id, tenantId, kiraUserId, virtualAccountId, recipientId, amount, fees,
                idempotencyKey, makerUserId);
        p.quotationId = quotationId;
        p.quotationExpiresAt = quotationExpiresAt;
        p.approvalState = approvalState;
        p.status = status;
        p.approverUserId = approverUserId;
        p.rejectionReason = rejectionReason;
        p.kiraPayoutId = kiraPayoutId;
        p.errorCode = errorCode;
        p.referenceNumber = referenceNumber;
        p.paymentMethod = paymentMethod;
        p.updatedAt = updatedAt;
        return p;
    }

    /** Lo que se debita de la cuenta virtual: importe enviado mas el cobro total al cliente. */
    public Money totalDebit() {
        return Money.of(fees.totalDebitFor(amount.amount()), amount.currency());
    }

    public BigDecimal platformMargin() {
        return fees.platformFee();
    }

    public void attachQuotation(String quotationId, Instant expiresAt) {
        this.quotationId = quotationId;
        this.quotationExpiresAt = expiresAt;
        touch();
    }

    /**
     * Ata el pago a una cotizacion vigente. NO la consume: la cotizacion se marca como
     * ejecutada al enviar el pago a Kira, no al prepararlo, porque entre preparar y
     * aprobar puede pasar de todo (incluido que venza y haya que recotizar).
     */
    public void attachQuotation(Quotation quotation, Instant now) {
        quotation.assertUsable(now);
        if (!quotation.getVirtualAccountId().equals(this.virtualAccountId)
                || !quotation.getRecipientId().equals(this.recipientId)) {
            throw new DomainException("La cotizacion es de otra cuenta o de otro destinatario.");
        }
        this.fees = quotation.getFees();
        attachQuotation(quotation.getId(), quotation.getExpiresAt());
    }

    public boolean isQuotationExpired(Instant now) {
        return quotationExpiresAt != null && !now.isBefore(quotationExpiresAt);
    }

    /**
     * Segregacion de funciones: quien crea la solicitud no puede autorizar su envio.
     */
    public void approve(String approverId, Instant now) {
        if (approvalState != PayoutApprovalState.PENDING_APPROVAL) {
            throw new DomainException("El pago no esta pendiente de aprobacion (estado actual: "
                    + approvalState + ").");
        }
        if (approverId == null || approverId.isBlank()) {
            throw new DomainException("Se requiere identificar al aprobador.");
        }
        if (makerUserId.equals(approverId)) {
            throw new DomainException(
                    "Violacion de control interno: quien crea la solicitud de pago no puede autorizar su envio.");
        }
        if (isQuotationExpired(now)) {
            throw new DomainException("La cotizacion vencio. Vuelve a cotizar antes de aprobar.");
        }
        this.approverUserId = approverId;
        this.approvalState = PayoutApprovalState.APPROVED;
        touch();
    }

    public void reject(String approverId, String reason) {
        if (approvalState != PayoutApprovalState.PENDING_APPROVAL) {
            throw new DomainException("Solo puede rechazarse un pago pendiente de aprobacion.");
        }
        this.approverUserId = approverId;
        this.rejectionReason = reason;
        this.approvalState = PayoutApprovalState.REJECTED;
        touch();
    }

    /** Se invoca tras un 201 de POST /v1/virtual-accounts/{id}/payout. */
    public void markAsSubmitted(String kiraPayoutId, String wireStatus) {
        if (approvalState != PayoutApprovalState.APPROVED) {
            throw new DomainException("No se puede enviar a Kira un pago sin aprobacion interna.");
        }
        this.kiraPayoutId = kiraPayoutId;
        this.approvalState = PayoutApprovalState.SUBMITTED;
        this.status = PayoutStatus.fromWire(wireStatus);
        if (this.status == PayoutStatus.UNKNOWN || this.status == PayoutStatus.NOT_SUBMITTED) {
            this.status = PayoutStatus.CREATED;
        }
        touch();
    }

    /**
     * Aplica una transicion recibida por webhook o por reconciliacion.
     * No retrocede desde un estado terminal: los eventos llegan una sola vez y sin orden garantizado.
     */
    public void applyRemoteStatus(PayoutStatus incoming, String errorCode) {
        if (incoming == null || incoming == PayoutStatus.UNKNOWN) {
            return;
        }
        if (this.status.isTerminal() && !incoming.isTerminal()) {
            return;
        }
        this.status = incoming;
        if (errorCode != null) {
            this.errorCode = errorCode;
        }
        touch();
    }

    public void fail(String reason) {
        this.status = PayoutStatus.FAILED;
        this.errorCode = reason;
        touch();
    }

    public boolean isReadyToSubmit() {
        return approvalState == PayoutApprovalState.APPROVED && kiraPayoutId == null;
    }

    /**
     * El importe que viaja en POST /payout.
     *
     * Kira DESCUENTA las comisiones del monto enviado: mandar 1.000 con 30 de comision deja
     * al destinatario con 970. Como se cotiza en modo inverse, el bruto que hay que enviar es
     * el total a debitar, y solo asi el destinatario recibe el importe prometido.
     */
    public Money grossAmountToSend(Quotation quotation) {
        if (quotation == null) {
            return Money.of(fees.totalDebitFor(amount.amount()), amount.currency());
        }
        return Money.of(quotation.getTotalDebitAmount(), amount.currency());
    }

    /** El precio esta cerrado solo si hay cotizacion detras. */
    public boolean isPriceLocked() {
        return quotationId != null;
    }

    public void assertSubmittable(Instant now) {
        if (approvalState != PayoutApprovalState.APPROVED) {
            throw new DomainException("No se puede enviar a Kira un pago sin aprobacion interna.");
        }
        if (kiraPayoutId != null) {
            throw new DomainException("Este pago ya fue enviado a Kira.");
        }
        if (isQuotationExpired(now)) {
            throw new DomainException("La cotizacion vencio. Vuelve a cotizar antes de enviar el pago.");
        }
    }

    /** Se completa desde el 201 del envio y desde GET /v1/payouts/{id}. */
    public void describeRemote(String referenceNumber, String paymentMethod) {
        if (referenceNumber != null && !referenceNumber.isBlank()) {
            this.referenceNumber = referenceNumber;
        }
        if (paymentMethod != null && !paymentMethod.isBlank()) {
            this.paymentMethod = paymentMethod;
        }
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
