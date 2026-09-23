package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PasswordHistoryJpaRepository extends JpaRepository<PasswordHistoryEntity, Long> {

    List<PasswordHistoryEntity> findByUserIdOrderByCreatedAtDescIdDesc(String userId, Pageable pageable);

    List<PasswordHistoryEntity> findByUserIdOrderByCreatedAtDescIdDesc(String userId);
}
