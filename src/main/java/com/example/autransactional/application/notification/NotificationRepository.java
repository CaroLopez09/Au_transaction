package com.example.autransactional.application.notification;

import com.example.autransactional.domain.shared.TenantId;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository {

    Notification save(Notification notification);

    List<Notification> findByTenant(TenantId tenantId, int limit);

    long countByTenantSince(TenantId tenantId, Instant since);

    /** Hasta cuando vio avisos este usuario; null si nunca. */
    Instant seenAt(String userId);

    void markSeen(String userId, Instant at);
}
