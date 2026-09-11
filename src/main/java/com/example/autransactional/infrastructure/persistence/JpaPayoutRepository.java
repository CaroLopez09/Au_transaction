package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.domain.treasury.PayoutStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class JpaPayoutRepository implements PayoutRepository {

    /** Estados no terminales que Kira ya conoce: son los que el reconciliador vuelve a preguntar. */
    private static final Set<PayoutStatus> IN_FLIGHT = Set.of(
            PayoutStatus.CREATED, PayoutStatus.PENDING, PayoutStatus.PROCESSING,
            PayoutStatus.KYT_PENDING, PayoutStatus.IN_REVIEW, PayoutStatus.UNKNOWN);

    private final PayoutJpaRepository jpa;

    public JpaPayoutRepository(PayoutJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Payout save(Payout payout) {
        PayoutEntity existing = jpa.findById(payout.getId()).orElse(null);
        jpa.save(PayoutMapper.toEntity(payout, existing));
        return payout;
    }

    @Override
    public Optional<Payout> findByIdAndTenant(String id, TenantId tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId.value()).map(PayoutMapper::toDomain);
    }

    @Override
    public Optional<Payout> findByIdempotencyKey(IdempotencyKey key) {
        return jpa.findByIdempotencyKey(key.value()).map(PayoutMapper::toDomain);
    }

    @Override
    public Optional<Payout> findByKiraPayoutId(String kiraPayoutId) {
        return jpa.findByKiraPayoutId(kiraPayoutId).map(PayoutMapper::toDomain);
    }

    @Override
    public List<Payout> findByTenant(TenantId tenantId, int limit) {
        return jpa.findByTenantIdOrderByCreatedAtDesc(tenantId.value())
                .stream()
                .limit(limit)
                .map(PayoutMapper::toDomain)
                .toList();
    }

    @Override
    public List<Payout> findInFlight(int limit) {
        return jpa.findByKiraPayoutIdIsNotNullAndStatusInOrderByUpdatedAtAsc(IN_FLIGHT)
                .stream()
                .limit(limit)
                .map(PayoutMapper::toDomain)
                .toList();
    }
}
