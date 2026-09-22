package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityVerificationAttemptJpaRepository extends JpaRepository<IdentityVerificationAttemptEntity, String> {
}