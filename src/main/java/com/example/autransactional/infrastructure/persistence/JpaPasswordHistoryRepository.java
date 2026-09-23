package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.tenant.PasswordHistoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class JpaPasswordHistoryRepository implements PasswordHistoryRepository {

    private final PasswordHistoryJpaRepository jpa;

    public JpaPasswordHistoryRepository(PasswordHistoryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<String> recentHashes(String userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jpa.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, limit)).stream()
                .map(PasswordHistoryEntity::getPasswordHash)
                .toList();
    }

    @Override
    @Transactional
    public void archive(String userId, String passwordHash) {
        PasswordHistoryEntity entity = new PasswordHistoryEntity();
        entity.setUserId(userId);
        entity.setPasswordHash(passwordHash);
        jpa.save(entity);
    }

    @Override
    @Transactional
    public void trim(String userId, int keep) {
        List<PasswordHistoryEntity> all = jpa.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        if (all.size() > keep) {
            jpa.deleteAll(all.subList(Math.max(keep, 0), all.size()));
        }
    }
}
