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
 *
 * Los nombres (maker, aprobadores y destinatario) viajan junto a sus ids porque el portal no
 * tiene forma de resolverlos: no hay directorio de operadores en el navegador y el listado de
 * destinatarios solo trae los activos, asi que un pago antiguo se quedaba sin nombre (G-03, G-26).
 */
public record PayoutView(
        String id,
        String virtualAccountId,
        String recipientId,
        /** Nombre del destinatario aunque este archivado (G-26). */
        String recipientName,
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
        /** Quien preparo el pago, con nombre y apellido (G-03). */
        String makerName,
        String approverUserId,
        String approverName,
        /** Primera firma registrada cuando el pago necesita dos. */
        String firstApproverUserId,
        String firstApproverName,
        /** 1 o 2, segun el umbral de la empresa (bff.payouts.approval). */
        int requiredApprovals,
        boolean priceLocked,
        String kiraPayoutId,
        String referenceNumber,
        String paymentMethod,
        String errorCode,
        String blockedByRfiId,
        /** No nulo cuando el pago se financia con un deposito cripto en vez del saldo (G-19). */
        String fundingNetwork,
        String fundingCurrency,
        /** JSON crudo de Kira con la direccion/red/vencimiento del deposito. Null si no aplica. */
        String depositInstructions,
        Instant createdAt,
        Instant updatedAt) {

    /** Sin directorio a mano (consola de plataforma): los ids siguen viajando, los nombres no. */
    public static PayoutView from(Payout p, String blockedByRfiId, int requiredApprovals) {
        return from(p, blockedByRfiId, requiredApprovals, null, null, null, null);
    }

    public static PayoutView from(Payout p, String blockedByRfiId, int requiredApprovals,
                                  String recipientName, String makerName, String approverName,
                                  String firstApproverName) {
        return new PayoutView(
                p.getId(),
                p.getVirtualAccountId(),
                p.getRecipientId(),
                recipientName,
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
                makerName,
                p.getApproverUserId(),
                approverName,
                p.getFirstApproverUserId(),
                firstApproverName,
                requiredApprovals,
                p.isPriceLocked(),
                p.getKiraPayoutId(),
                p.getReferenceNumber(),
                p.getPaymentMethod(),
                p.getErrorCode(),
                blockedByRfiId,
                p.getFundingNetwork(),
                p.getFundingCurrency(),
                p.getDepositInstructions(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
