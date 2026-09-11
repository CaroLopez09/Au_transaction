package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventJpaRepository extends JpaRepository<WebhookEventEntity, String> {

    boolean existsByEventId(String eventId);

    Optional<WebhookEventEntity> findByEventId(String eventId);
}
