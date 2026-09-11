package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleJpaRepository extends JpaRepository<RoleEntity, String> {

    Optional<RoleEntity> findByName(String name);
}
