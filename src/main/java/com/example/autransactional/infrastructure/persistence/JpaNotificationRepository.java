package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.application.notification.Notification;
import com.example.autransactional.application.notification.NotificationRepository;
import com.example.autransactional.domain.shared.TenantId;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class JpaNotificationRepository implements NotificationRepository {

    private final NotificationJpaRepository jpa;
    private final OperatorUserJpaRepository users;

    public JpaNotificationRepository(NotificationJpaRepository jpa, OperatorUserJpaRepository users) {
        this.jpa = jpa;
        this.users = users;
    }

    @Override
    public Notification save(Notification n) {
        NotificationEntity e = new NotificationEntity();
        e.setId(n.id());
        e.setTenantId(n.tenantId().value());
        e.setKind(n.kind());
        e.setSeverity(n.severity());
        e.setTitle(truncate(n.title(), 160));
        e.setMessage(truncate(n.message(), 500));
        e.setResourceType(n.resourceType());
        e.setResourceId(n.resourceId());
        e.setCreatedAt(n.createdAt());
        jpa.save(e);
        return n;
    }

    @Override
    public List<Notification> findByTenant(TenantId tenantId, int limit) {
        return jpa.findByTenantIdOrderByCreatedAtDesc(tenantId.value(), PageRequest.of(0, limit)).stream()
                .map(e -> new Notification(e.getId(), TenantId.of(e.getTenantId()), e.getKind(), e.getSeverity(),
                        e.getTitle(), e.getMessage(), e.getResourceType(), e.getResourceId(), e.getCreatedAt()))
                .toList();
    }

    @Override
    public long countByTenantSince(TenantId tenantId, Instant since) {
        return jpa.countByTenantIdAndCreatedAtAfter(tenantId.value(), since);
    }

    @Override
    public Instant seenAt(String userId) {
        return users.findById(userId).map(OperatorUserEntity::getNotificationsSeenAt).orElse(null);
    }

    @Override
    public void markSeen(String userId, Instant at) {
        users.findById(userId).ifPresent(u -> {
            u.setNotificationsSeenAt(at);
            users.save(u);
        });
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
