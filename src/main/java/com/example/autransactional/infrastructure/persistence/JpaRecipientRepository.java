package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.treasury.Recipient;
import com.example.autransactional.domain.treasury.RecipientRepository;
import com.example.autransactional.domain.treasury.RecipientStatus;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaRecipientRepository implements RecipientRepository {

    private final RecipientJpaRepository jpa;
    private final RecipientMapper mapper;

    public JpaRecipientRepository(RecipientJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.mapper = new RecipientMapper(objectMapper);
    }

    @Override
    public Recipient save(Recipient recipient) {
        RecipientEntity existing = jpa.findById(recipient.getId()).orElse(null);
        jpa.save(mapper.toEntity(recipient, existing));
        return recipient;
    }

    @Override
    public Optional<Recipient> findByIdAndTenant(String id, TenantId tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId.value()).map(mapper::toDomain);
    }

    @Override
    public Optional<Recipient> findByKiraRecipientId(String kiraRecipientId) {
        return jpa.findByKiraRecipientId(kiraRecipientId).map(mapper::toDomain);
    }

    @Override
    public List<Recipient> findActiveByTenant(TenantId tenantId) {
        return jpa.findByTenantIdAndStatusOrderByNameAsc(tenantId.value(), RecipientStatus.ACTIVE)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
