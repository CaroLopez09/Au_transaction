package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantJpaRepository extends JpaRepository<TenantEntity, String> {

    Optional<TenantEntity> findByKiraUserId(String kiraUserId);

    Optional<TenantEntity> findByNameIgnoreCase(String name);
}
