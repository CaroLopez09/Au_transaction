package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.treasury.Payout;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Proyeccion estable para el frontend: no expone la forma cambiante de la respuesta de Kira.
 *
 * amount es lo que recibe el destinatario y totalDebitAmount el bruto que sale de la cuenta.
 * referenceNumber (IMAD / ACH trace / UETR) es el comprobante que reclama el cliente final.
 * blockedByRfiId no es nulo cuando un RFI abierto de Kira tiene el pago detenido: el portal
 * lo muestra como "detenido" y enlaza al RFI.
 */
public record PayoutView(
        String id,
        String virtualAccountId,
        String recipientId,
        String quotationId,
        BigDecimal amount,
        String currency,
        BigDecimal kiraFee,
        BigDecimal platformFee,
        BigDecimal totalFee,
        BigDecimal totalDebitAmount,
        String approvalState,
        String status,
        boolean terminal,
        String makerUserId,
        String approverUserId,
        /** Primera firma registrada cuando el pago necesita dos. */
        String firstApproverUserId,
        /** 1 o 2, segun el umbral de la empresa (bff.payouts.approval). */
        int requiredApprovals,
        boolean priceLocked,
        String kiraPayoutId,
        String referenceNumber,
        String paymentMethod,
        String errorCode,
        String blockedByRfiId,
        Instant createdAt,
        Instant updatedAt) {

    public static PayoutView from(Payout p, String blockedByRfiId, int requiredApprovals) {
        return new PayoutView(
                p.getId(),
                p.getVirtualAccountId(),
                p.getRecipientId(),
                p.getQuotationId(),
                p.getAmount().amount(),
                p.getAmount().currency(),
                p.getFees().kiraFee(),
                p.getFees().platformFee(),
                p.getFees().totalFee(),
                p.totalDebit().amount(),
                p.getApprovalState().name(),
                p.getStatus().name(),
                p.getStatus().isTerminal(),
                p.getMakerUserId(),
                p.getApproverUserId(),
                p.getFirstApproverUserId(),
                requiredApprovals,
                p.isPriceLocked(),
                p.getKiraPayoutId(),
                p.getReferenceNumber(),
                p.getPaymentMethod(),
                p.getErrorCode(),
                blockedByRfiId,
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
