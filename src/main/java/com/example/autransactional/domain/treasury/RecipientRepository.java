package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

public interface RecipientRepository {

    Recipient save(Recipient recipient);

    Optional<Recipient> findByIdAndTenant(String id, TenantId tenantId);

    Optional<Recipient> findByKiraRecipientId(String kiraRecipientId);

    List<Recipient> findActiveByTenant(TenantId tenantId);
}
