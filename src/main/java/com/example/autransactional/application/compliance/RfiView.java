package com.example.autransactional.application.compliance;

import com.example.autransactional.domain.account.Deposit;
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
     * Lo que el RFI tiene detenido: una transferencia (type "transfer") o un deposito
     * (type "virtual_account_deposit"). payoutId / depositId son los ids del portal; van nulos
     * si Kira bloquea algo que este portal no conoce.
     */
    public record Blocking(String type, String kiraResourceId, String payoutId, String payoutStatus,
                           String depositId, String depositStatus) {
    }

    public static RfiView from(Rfi rfi, List<Map<String, Object>> items, Payout blockedPayout,
                               Deposit blockedDeposit) {
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
                blockedPayout == null ? null : blockedPayout.getStatus().name(),
                blockedDeposit == null ? null : blockedDeposit.getId(),
                blockedDeposit == null ? null : blockedDeposit.getStatus().name());

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
