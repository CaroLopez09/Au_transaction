package com.example.autransactional.domain.compliance.identity;

import com.example.autransactional.domain.shared.TenantId;

import java.util.Optional;

public interface VerificationSessionRepository {

    VerificationSession save(VerificationSession session);

    Optional<VerificationSession> findById(String id);

    Optional<VerificationSession> findByIdAndTenant(String id, TenantId tenantId);

    Optional<VerificationSession> findByChallengeId(String challengeId);

    Optional<VerificationSession> findByLivenessSessionId(String livenessSessionId);
}
