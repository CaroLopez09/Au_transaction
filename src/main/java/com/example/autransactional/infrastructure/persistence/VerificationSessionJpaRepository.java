package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationSessionJpaRepository extends JpaRepository<VerificationSessionEntity, String> {

    Optional<VerificationSessionEntity> findByIdAndTenantId(String id, String tenantId);

    Optional<VerificationSessionEntity> findByChallengeId(String challengeId);

    Optional<VerificationSessionEntity> findByLivenessSessionId(String livenessSessionId);
}
