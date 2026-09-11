package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.treasury.FeeBreakdown;
import com.example.autransactional.domain.treasury.Quotation;
import com.example.autransactional.domain.treasury.QuotationRepository;
import com.example.autransactional.domain.treasury.QuotationStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaQuotationRepository implements QuotationRepository {

    private final QuotationJpaRepository jpa;

    public JpaQuotationRepository(QuotationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Quotation save(Quotation quotation) {
        QuotationEntity e = jpa.findById(quotation.getId()).orElseGet(QuotationEntity::new);
        e.setId(quotation.getId());
        e.setTenantId(quotation.getTenantId().value());
        e.setVirtualAccountId(quotation.getVirtualAccountId());
        e.setRecipientId(quotation.getRecipientId());
        e.setKiraQuoteId(quotation.getKiraQuoteId());
        e.setRail(quotation.getRail());
        e.setDestinationCurrency(quotation.getDestinationCurrency());
        e.setBalanceSufficient(quotation.isBalanceSufficient());
        e.setRateSource(quotation.getRateSource());
        e.setFeesSnapshot(quotation.getFeesSnapshot());
        e.setOriginAmount(quotation.getOriginAmount());
        e.setDestinationAmount(quotation.getDestinationAmount());
        e.setExchangeRate(quotation.getExchangeRate());
        e.setKiraFee(quotation.getFees().kiraFee());
        e.setPlatformFee(quotation.getFees().platformFee());
        e.setTotalFee(quotation.getFees().totalFee());
        e.setTotalDebitAmount(quotation.getTotalDebitAmount());
        e.setQuoteExpiresAt(quotation.getExpiresAt());
        e.setStatus(quotation.getStatus());
        e.setCreatedAt(quotation.getCreatedAt());
        jpa.save(e);
        return quotation;
    }

    @Override
    public Optional<Quotation> findByIdAndTenant(String id, TenantId tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId.value()).map(JpaQuotationRepository::toDomain);
    }

    @Override
    public List<Quotation> findByTenant(TenantId tenantId, int limit) {
        return jpa.findByTenantIdOrderByCreatedAtDesc(tenantId.value()).stream()
                .limit(limit)
                .map(JpaQuotationRepository::toDomain)
                .toList();
    }

    @Override
    public List<Quotation> findActiveExpiredBefore(Instant cutoff) {
        return jpa.findByStatusAndQuoteExpiresAtBefore(QuotationStatus.ACTIVE, cutoff).stream()
                .map(JpaQuotationRepository::toDomain)
                .toList();
    }

    private static Quotation toDomain(QuotationEntity e) {
        return Quotation.rehydrate(e.getId(), TenantId.of(e.getTenantId()), e.getVirtualAccountId(),
                e.getRecipientId(), e.getRail(), e.getKiraQuoteId(), e.getOriginAmount(),
                e.getDestinationAmount(), e.getDestinationCurrency(), e.getExchangeRate(),
                new FeeBreakdown(e.getKiraFee(), e.getPlatformFee(), e.getTotalFee()),
                e.getTotalDebitAmount(), e.isBalanceSufficient(), e.getRateSource(), e.getFeesSnapshot(),
                e.getQuoteExpiresAt(), e.getStatus(), e.getCreatedAt());
    }
}
