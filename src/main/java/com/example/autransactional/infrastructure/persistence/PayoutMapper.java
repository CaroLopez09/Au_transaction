package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.treasury.FeeBreakdown;
import com.example.autransactional.domain.treasury.Payout;

final class PayoutMapper {

    private PayoutMapper() {
    }

    static Payout toDomain(PayoutEntity e) {
        return Payout.rehydrate(
                e.getId(),
                TenantId.of(e.getTenantId()),
                e.getKiraUserId(),
                e.getVirtualAccountId(),
                e.getRecipientId(),
                Money.of(e.getAmount(), e.getCurrency()),
                new FeeBreakdown(e.getKiraFee(), e.getPlatformFee(), e.getTotalFee()),
                IdempotencyKey.of(e.getIdempotencyKey()),
                e.getMakerUserId(),
                e.getCreatedAt(),
                e.getQuotationId(),
                e.getQuotationExpiresAt(),
                e.getApprovalState(),
                e.getStatus(),
                e.getApproverUserId(),
                e.getFirstApproverUserId(),
                e.getRejectionReason(),
                e.getKiraPayoutId(),
                e.getErrorCode(),
                e.getReferenceNumber(),
                e.getPaymentMethod(),
                e.getFundingNetwork(),
                e.getFundingCurrency(),
                e.getDepositInstructions(),
                e.getUpdatedAt());
    }

    static PayoutEntity toEntity(Payout p, PayoutEntity target) {
        PayoutEntity e = target != null ? target : new PayoutEntity();
        e.setId(p.getId());
        e.setTenantId(p.getTenantId().value());
        e.setKiraUserId(p.getKiraUserId());
        e.setVirtualAccountId(p.getVirtualAccountId());
        e.setRecipientId(p.getRecipientId());
        e.setAmount(p.getAmount().amount());
        e.setCurrency(p.getAmount().currency());
        e.setKiraFee(p.getFees().kiraFee());
        e.setPlatformFee(p.getFees().platformFee());
        e.setTotalFee(p.getFees().totalFee());
        e.setIdempotencyKey(p.getIdempotencyKey().value());
        e.setMakerUserId(p.getMakerUserId());
        e.setApproverUserId(p.getApproverUserId());
        e.setFirstApproverUserId(p.getFirstApproverUserId());
        e.setQuotationId(p.getQuotationId());
        e.setQuotationExpiresAt(p.getQuotationExpiresAt());
        e.setApprovalState(p.getApprovalState());
        e.setStatus(p.getStatus());
        e.setRejectionReason(p.getRejectionReason());
        e.setKiraPayoutId(p.getKiraPayoutId());
        e.setErrorCode(p.getErrorCode());
        e.setReferenceNumber(p.getReferenceNumber());
        e.setPaymentMethod(p.getPaymentMethod());
        e.setFundingNetwork(p.getFundingNetwork());
        e.setFundingCurrency(p.getFundingCurrency());
        e.setDepositInstructions(p.getDepositInstructions());
        e.setCreatedAt(p.getCreatedAt());
        e.setUpdatedAt(p.getUpdatedAt());
        return e;
    }
}
