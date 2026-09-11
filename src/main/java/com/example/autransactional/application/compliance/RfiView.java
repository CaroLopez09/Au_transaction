package com.example.autransactional.application.compliance;

import com.example.autransactional.domain.compliance.Rfi;
import com.example.autransactional.domain.treasury.Payout;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RFI para la bandeja y el formulario de respuesta.
 *
 * Los items van tal como los entrega Kira: el formulario se genera desde answer_spec y
 * cada tipo nuevo de requerimiento debe poder mostrarse sin desplegar el BFF.
 */
public record RfiView(
        String id,
        String kiraRfiId,
        String status,
        boolean open,
        boolean overdue,
        Instant dueDate,
        int totalItems,
        int pendingItems,
        List<Map<String, Object>> items,
        Blocking blocking,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Lo que el RFI tiene detenido. payoutId es el pago local; va nulo si Kira bloquea algo
     * que este portal no origino.
     */
    public record Blocking(String type, String kiraResourceId, String payoutId, String payoutStatus) {
    }

    public static RfiView from(Rfi rfi, List<Map<String, Object>> items, Payout blockedPayout) {
        int pending = 0;
        for (Map<String, Object> item : items) {
            if ("pending".equalsIgnoreCase(String.valueOf(item.get("status")))) {
                pending++;
            }
        }
        Blocking blocking = rfi.getBlockingResourceId() == null ? null : new Blocking(
                rfi.getBlockingType(),
                rfi.getBlockingResourceId(),
                blockedPayout == null ? null : blockedPayout.getId(),
                blockedPayout == null ? null : blockedPayout.getStatus().name());

        return new RfiView(
                rfi.getId(),
                rfi.getKiraRfiId(),
                rfi.getStatus().name(),
                rfi.getStatus().isOpen(),
                rfi.isOverdue(Instant.now()),
                rfi.getDueDate(),
                items.size(),
                pending,
                items,
                blocking,
                rfi.getCreatedAt(),
                rfi.getUpdatedAt());
    }
}
