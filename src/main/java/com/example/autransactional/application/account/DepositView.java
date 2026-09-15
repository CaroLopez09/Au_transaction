package com.example.autransactional.application.account;

import com.example.autransactional.domain.account.Deposit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Deposito para el portal.
 *
 * Los tres importes van por separado porque son tres hechos distintos: lo que envio el
 * ordenante, lo que cobro el banco y lo que quedo disponible.
 */
public record DepositView(
        String id,
        String kiraDepositId,
        String virtualAccountId,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        String currency,
        String senderName,
        String senderAccount,
        String rail,
        String status,
        boolean microdeposit,
        boolean creditsBalance,
        /** Retenido por cumplimiento (KYT_PENDING o KYT_REJECTED): mientras dure, la cuenta no paga. */
        boolean held,
        Instant createdAt,
        Instant updatedAt) {

    public static DepositView from(Deposit d) {
        return new DepositView(
                d.getId(),
                d.getKiraDepositId(),
                d.getVirtualAccountId(),
                d.getGrossAmount(),
                d.getFeeAmount(),
                d.getNetAmount(),
                d.getCurrency(),
                d.getSenderName(),
                maskAccount(d.getSenderAccount()),
                d.getRail() == null ? null : d.getRail().name(),
                d.getStatus().name(),
                d.isMicrodeposit(),
                d.creditsBalance(),
                d.getStatus().isHeld(),
                d.getCreatedAt(),
                d.getUpdatedAt());
    }

    /** La cuenta del ordenante nunca sale completa hacia el navegador (G-25). */
    static String maskAccount(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        // Mismo formato que RecipientView.maskedDestination.
        String trimmed = account.trim();
        return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
    }
}
