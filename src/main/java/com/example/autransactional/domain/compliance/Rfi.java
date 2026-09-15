package com.example.autransactional.domain.compliance;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import lombok.Getter;

import java.time.Instant;

/**
 * Agregado Rfi: requerimiento de informacion de KiraFin sobre una empresa cliente.
 *
 * Nunca se crea desde aqui: Kira lo genera y nosotros lo leemos y respondemos. Los items
 * llegan como un array libre que cambia por tipo de requerimiento; se guardan tal cual
 * (JSON) porque normalizarlos obligaria a migrar el esquema cada vez que Kira pide algo
 * nuevo. Lo que si es del dominio es el estado, el plazo y lo que el RFI tiene bloqueado.
 */
@Getter
public class Rfi {

    private final String id;
    private final TenantId tenantId;
    private final Instant createdAt;

    private String kiraRfiId;
    private RfiStatus status;
    private String itemsPayload;
    private Instant dueDate;
    private String blockingType;
    private String blockingResourceId;
    /** expired o rejected. Solo en un RFI not_resolved: la diferencia entre plazo vencido y respuesta rechazada. */
    private String resolutionReason;
    private Instant updatedAt;

    public Rfi(String id, TenantId tenantId, String kiraRfiId, String itemsPayload, Instant dueDate) {
        this(id, tenantId, kiraRfiId, itemsPayload, dueDate, Instant.now());
    }

    private Rfi(String id, TenantId tenantId, String kiraRfiId, String itemsPayload, Instant dueDate,
                Instant createdAt) {
        if (itemsPayload == null || itemsPayload.isBlank()) {
            throw new DomainException("Un RFI sin items no es accionable.");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.kiraRfiId = kiraRfiId;
        this.itemsPayload = itemsPayload;
        this.dueDate = dueDate;
        this.status = RfiStatus.PENDING;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = this.createdAt;
    }

    public static Rfi rehydrate(String id, TenantId tenantId, String kiraRfiId, RfiStatus status,
                                String itemsPayload, Instant dueDate, String blockingType,
                                String blockingResourceId, String resolutionReason,
                                Instant createdAt, Instant updatedAt) {
        Rfi r = new Rfi(id, tenantId, kiraRfiId, itemsPayload, dueDate, createdAt);
        r.status = status == null ? RfiStatus.PENDING : status;
        r.blockingType = blockingType;
        r.blockingResourceId = blockingResourceId;
        r.resolutionReason = resolutionReason;
        r.updatedAt = updatedAt;
        return r;
    }

    /** Antes de llamar a Kira: responder un RFI cerrado solo gasta la llamada y devuelve 409. */
    public void assertAcceptsAnswers() {
        if (!status.isOpen()) {
            throw new DomainException("Este RFI ya esta cerrado (" + status + ") y no admite respuestas.");
        }
    }

    /**
     * Asienta lo que dice Kira. Kira es la fuente de verdad, con una excepcion: un RFI cerrado
     * no se reabre. Los webhooks pueden llegar desordenados, y un 'answered' tardio no debe
     * devolver a la bandeja algo ya resuelto.
     */
    public void applyRemoteStatus(RfiStatus incoming, String itemsPayload) {
        if (incoming != null && !(status.isTerminal() && !incoming.isTerminal())) {
            this.status = incoming;
        }
        if (itemsPayload != null && !itemsPayload.isBlank()) {
            this.itemsPayload = itemsPayload;
        }
        touch();
    }

    /** El plazo no se prorroga aunque Kira devuelva items para otra ronda. */
    public void describeDueDate(Instant dueDate) {
        if (dueDate != null) {
            this.dueDate = dueDate;
        }
    }

    /** blocking: { type: "transfer", transfer_uuid }. Lo bloqueado sigue bloqueado si el RFI vence. */
    public void describeBlocking(String type, String resourceId) {
        if (resourceId != null && !resourceId.isBlank()) {
            this.blockingType = type;
            this.blockingResourceId = resourceId;
        }
    }

    public void describeResolutionReason(String reason) {
        if (reason != null && !reason.isBlank()) {
            this.resolutionReason = reason.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Kira lo retiro: responde 404 y ya no hay nada que contestar. */
    public void withdraw() {
        if (!status.isTerminal()) {
            this.status = RfiStatus.WITHDRAWN;
            this.resolutionReason = "withdrawn";
            touch();
        }
    }

    public boolean isOverdue(Instant now) {
        return dueDate != null && status.isOpen() && !now.isBefore(dueDate);
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
