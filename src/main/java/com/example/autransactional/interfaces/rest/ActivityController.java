package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.audit.AuditQueryService;
import com.example.autransactional.application.notification.NotificationService;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Avisos, centro de eventos y auditoria de la organizacion. */
@Tag(name = "7. Actividad", description = "Avisos de negocio, eventos de Kira recibidos y bitacora de auditoria.")
@RestController
@RequestMapping("/api")
public class ActivityController {

    private final NotificationService notifications;
    private final AuditQueryService audit;

    public ActivityController(NotificationService notifications, AuditQueryService audit) {
        this.notifications = notifications;
        this.audit = audit;
    }

    @GetMapping("/notifications")
    public NotificationService.NotificationFeed notifications(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestParam(defaultValue = "50") int limit) {
        return notifications.feed(operator, limit);
    }

    /** Para el contador de la campana: barato de consultar a menudo. */
    @GetMapping("/notifications/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return Map.of("unread", notifications.unreadCount(operator));
    }

    @PostMapping("/notifications/read")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal AuthenticatedOperator operator) {
        notifications.markAllRead(operator);
        return ResponseEntity.noContent().build();
    }

    /** Tabla tecnica de eventos de Kira recibidos: tipo, recurso y estado de procesamiento. */
    @GetMapping("/events")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public List<AuditQueryService.EventView> events(@AuthenticationPrincipal AuthenticatedOperator operator,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return audit.events(operator, limit);
    }

    /** Solo los eventos con al menos un fallo de proyeccion: el panel de incidencias de integracion. */
    @GetMapping("/events/incidents")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public List<AuditQueryService.EventView> incidents(@AuthenticationPrincipal AuthenticatedOperator operator,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return audit.incidents(operator, limit);
    }

    /** Reintenta ahora, sin esperar al worker programado. Vuelve a fallar igual que un reintento automatico. */
    @PostMapping("/events/{eventId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public AuditQueryService.EventView retry(@AuthenticationPrincipal AuthenticatedOperator operator,
                                             @PathVariable String eventId) {
        return audit.retry(operator, eventId);
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")
    public List<AuditQueryService.AuditEntryView> auditTrail(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestParam(defaultValue = "100") int limit) {
        return audit.auditTrail(operator, limit);
    }
}
