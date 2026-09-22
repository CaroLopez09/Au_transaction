package com.example.autransactional.application.audit;

import com.example.autransactional.application.webhook.ProcessWebhookUseCase;
import com.example.autransactional.domain.compliance.AuditLog;
import com.example.autransactional.domain.compliance.AuditLogRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.infrastructure.persistence.WebhookEventEntity;
import com.example.autransactional.infrastructure.persistence.WebhookEventJpaRepository;
import com.example.autransactional.infrastructure.reconciliation.WebhookReprojectionWorker;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lectura de la trazabilidad propia (arquitectura §2.2 y §2.6): la bitacora de acciones y el
 * centro de eventos de Kira. Siempre filtrado por la organizacion del operador.
 */
@Service
public class AuditQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

    static final int MAX_LIMIT = 200;

    private final AuditLogRepository audit;
    private final OperatorUserRepository users;
    private final WebhookEventJpaRepository events;
    private final ProcessWebhookUseCase webhooks;

    public AuditQueryService(AuditLogRepository audit, OperatorUserRepository users,
                             WebhookEventJpaRepository events, ProcessWebhookUseCase webhooks) {
        this.audit = audit;
        this.users = users;
        this.events = events;
        this.webhooks = webhooks;
    }

    @Transactional(readOnly = true)
    public List<AuditEntryView> auditTrail(AuthenticatedOperator operator, int limit) {
        List<AuditLog> entries = audit.findByTenant(operator.tenantId(), clamp(limit));
        // El actor se muestra por nombre y correo, no por su id interno.
        Map<String, OperatorUser> actores = users.findByTenant(operator.tenantId()).stream()
                .collect(Collectors.toMap(OperatorUser::id, Function.identity()));
        return entries.stream().map(e -> {
            OperatorUser actor = e.userId() == null ? null : actores.get(e.userId());
            return new AuditEntryView(e.id(), e.action(), e.resourceType(), e.resourceId(),
                    actor == null ? null : actor.fullName(), actor == null ? null : actor.email(),
                    e.userRole() == null ? null : e.userRole().name(), e.changes(), e.createdAt());
        }).toList();
    }

    /** Sin payload: puede traer datos personales y aqui solo interesa que paso y si se proceso. */
    @Transactional(readOnly = true)
    public List<EventView> events(AuthenticatedOperator operator, int limit) {
        return events.findByTenantIdOrderByCreatedAtDesc(operator.tenantId().value(),
                        PageRequest.of(0, clamp(limit)))
                .stream().map(EventView::from).toList();
    }

    /**
     * Incidencias de integracion (G-webhook-log): eventos con al menos un fallo de proyeccion.
     * `exhausted` distingue los que el worker ya abandono (agotaron sus reintentos) de los que
     * aun se van a reintentar solos: solo los primeros necesitan intervencion o escalar a soporte.
     */
    @Transactional(readOnly = true)
    public List<EventView> incidents(AuthenticatedOperator operator, int limit) {
        return events.findByTenantIdAndProcessingErrorIsNotNullOrderByCreatedAtDesc(
                        operator.tenantId().value(), PageRequest.of(0, clamp(limit)))
                .stream().map(EventView::from).toList();
    }

    /**
     * Reintento manual de una incidencia: limpia el error y vuelve a intentar la proyeccion en el
     * momento, sin esperar al worker programado. Si vuelve a fallar, queda registrado igual que
     * un reintento automatico.
     */
    @Transactional
    public EventView retry(AuthenticatedOperator operator, String eventId) {
        WebhookEventEntity event = events.findByEventId(eventId)
                .filter(e -> operator.tenantId().value().equals(e.getTenantId()))
                .orElseThrow(() -> new DomainException("El evento no existe."));
        event.setRetryCount(0);
        event.setProcessingError(null);
        try {
            webhooks.reproject(event);
        } catch (Exception e) {
            event.setRetryCount(1);
            event.setProcessingError(truncate(e.getMessage()));
            log.warn("Reintento manual del evento {} sigue fallando: {}", eventId, e.getMessage());
        }
        events.save(event);
        return EventView.from(event);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    public record AuditEntryView(String id, String action, String resourceType, String resourceId,
                                 String actorName, String actorEmail, String actorRole, String detail,
                                 Instant createdAt) {
    }

    public record EventView(String eventId, String eventType, String resourceId, String status,
                            boolean processed, String processingError, int retryCount, boolean exhausted,
                            Instant receivedAt, Instant processedAt) {

        static EventView from(WebhookEventEntity e) {
            return new EventView(e.getEventId(), e.getEventType(), e.getResourceId(), e.getNormalizedStatus(),
                    e.isProcessed(), e.getProcessingError(), e.getRetryCount(),
                    e.getRetryCount() >= WebhookReprojectionWorker.MAX_RETRIES, e.getCreatedAt(),
                    e.getProcessedAt());
        }
    }
}
