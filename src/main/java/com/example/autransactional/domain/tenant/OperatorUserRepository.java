package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

public interface OperatorUserRepository {

    Optional<OperatorUser> findByEmail(String email);

    Optional<OperatorUser> findById(String id);

    List<OperatorUser> findByTenant(TenantId tenantId);

    /** Guarda el secreto TOTP ya cifrado y si esta activo. Un secreto nulo quita el segundo factor. */
    void updateMfa(String userId, String encryptedSecret, boolean enabled);
}
