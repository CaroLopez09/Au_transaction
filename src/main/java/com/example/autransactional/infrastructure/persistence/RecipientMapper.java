package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.PostalAddress;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.treasury.Recipient;
import com.example.autransactional.domain.treasury.RecipientAccount;
import com.example.autransactional.domain.treasury.RecipientHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * Aplana el oneOf del destinatario sobre la tabla y lo reconstruye.
 * El riel decide que bloque de columnas se usa; el resto quedan nulas.
 */
final class RecipientMapper {

    private final ObjectMapper objectMapper;

    RecipientMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    RecipientEntity toEntity(Recipient r, RecipientEntity target) {
        RecipientEntity e = target != null ? target : new RecipientEntity();
        e.setId(r.getId());
        e.setTenantId(r.getTenantId().value());
        e.setKiraRecipientId(r.getKiraRecipientId());
        e.setName(r.getName());
        e.setRail(r.getRail());
        e.setStatus(r.getStatus());
        e.setReplacedByRecipientId(r.getReplacedByRecipientId());
        e.setCreatedByUserId(r.getCreatedByUserId());

        RecipientHolder holder = r.getHolder();
        e.setBusiness(holder.business());
        e.setFirstName(holder.firstName());
        e.setLastName(holder.lastName());
        e.setCompanyName(holder.companyName());
        e.setEmail(holder.email());
        e.setPhone(holder.phone());
        e.setHolderAddress(write(r.getAddress()));

        switch (r.getAccount()) {
            case RecipientAccount.Ach ach -> {
                e.setRoutingNumber(ach.routingNumber());
                e.setAccountNumber(ach.accountNumber());
                e.setAccountKind(ach.kind());
                e.setBankName(ach.bankName());
                e.setBankAddressText(ach.bankAddressText());
                e.setDocType(ach.docType());
                e.setDocNumber(ach.docNumber());
            }
            case RecipientAccount.Wire wire -> {
                e.setRoutingNumber(wire.routingNumber());
                e.setSwiftCode(wire.swiftCode());
                e.setAccountNumber(wire.accountNumber());
                e.setAccountKind(wire.kind());
                e.setBankName(wire.bankName());
                e.setBankAddress(write(wire.bankAddress()));
                e.setDocType(wire.docType());
                e.setDocNumber(wire.docNumber());
            }
            case RecipientAccount.Wallet wallet -> {
                e.setWalletToken(wallet.token());
                e.setNetwork(wallet.network());
                e.setWalletAddress(wallet.address());
                e.setDocType(wallet.docType());
                e.setDocNumber(wallet.docNumber());
            }
        }

        e.setCreatedAt(r.getCreatedAt());
        e.setUpdatedAt(r.getUpdatedAt());
        return e;
    }

    Recipient toDomain(RecipientEntity e) {
        RecipientHolder holder = new RecipientHolder(e.isBusiness(), e.getFirstName(), e.getLastName(),
                e.getCompanyName(), e.getEmail(), e.getPhone());

        RecipientAccount account = switch (e.getRail()) {
            case ACH -> new RecipientAccount.Ach(e.getRoutingNumber(), e.getAccountNumber(),
                    e.getAccountKind(), e.getBankName(), e.getBankAddressText(),
                    e.getDocType(), e.getDocNumber());
            case WIRE -> new RecipientAccount.Wire(e.getRoutingNumber(), e.getSwiftCode(),
                    e.getAccountNumber(), e.getAccountKind(), e.getBankName(),
                    read(e.getBankAddress()), e.getDocType(), e.getDocNumber());
            case WALLET -> new RecipientAccount.Wallet(e.getWalletToken(), e.getNetwork(),
                    e.getWalletAddress(), e.getDocType(), e.getDocNumber());
        };

        Recipient recipient = Recipient.rehydrate(e.getId(), TenantId.of(e.getTenantId()), holder, account,
                read(e.getHolderAddress()), e.getKiraRecipientId(), e.getStatus(),
                e.getReplacedByRecipientId(), e.getCreatedAt(), e.getUpdatedAt());
        recipient.recordAuthor(e.getCreatedByUserId());
        return recipient;
    }

    private String write(PostalAddress address) {
        return address == null ? null : objectMapper.writeValueAsString(address);
    }

    private PostalAddress read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PostalAddress.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
