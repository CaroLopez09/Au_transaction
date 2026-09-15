package com.example.autransactional.application.notification;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Centro de avisos dentro de la aplicacion. Los leidos se calculan por usuario con una marca de
 * "visto hasta": marcar todo como leido es mover esa marca, no tocar cada aviso.
 */
@Service
public class NotificationService {

    static final int MAX_LIMIT = 100;

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional
    public void notify(TenantId tenantId, String kind, String severity, String title, String message,
                       String resourceType, String resourceId) {
        if (tenantId == null) {
            return;
        }
        notifications.save(new Notification(UUID.randomUUID().toString(), tenantId, kind, severity, title,
                message, resourceType, resourceId, Instant.now()));
    }

    @Transactional(readOnly = true)
    public NotificationFeed feed(AuthenticatedOperator operator, int limit) {
        Instant seen = notifications.seenAt(operator.userId());
        List<NotificationView> items = notifications.findByTenant(operator.tenantId(),
                        Math.max(1, Math.min(limit, MAX_LIMIT))).stream()
                .map(n -> NotificationView.from(n, seen))
                .toList();
        return new NotificationFeed(items, unread(operator, seen));
    }

    @Transactional(readOnly = true)
    public long unreadCount(AuthenticatedOperator operator) {
        return unread(operator, notifications.seenAt(operator.userId()));
    }

    @Transactional
    public void markAllRead(AuthenticatedOperator operator) {
        notifications.markSeen(operator.userId(), Instant.now());
    }

    private long unread(AuthenticatedOperator operator, Instant seen) {
        return notifications.countByTenantSince(operator.tenantId(), seen == null ? Instant.EPOCH : seen);
    }

    public record NotificationView(String id, String kind, String severity, String title, String message,
                                   String resourceType, String resourceId, Instant createdAt, boolean unread) {

        static NotificationView from(Notification n, Instant seen) {
            return new NotificationView(n.id(), n.kind(), n.severity(), n.title(), n.message(), n.resourceType(),
                    n.resourceId(), n.createdAt(), seen == null || n.createdAt().isAfter(seen));
        }
    }

    public record NotificationFeed(List<NotificationView> items, long unread) {
    }
}
