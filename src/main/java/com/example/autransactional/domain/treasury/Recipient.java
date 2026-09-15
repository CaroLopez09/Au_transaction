package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.PostalAddress;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import lombok.Getter;

import java.time.Instant;

/**
 * Destinatario del directorio de pagos de la empresa cliente.
 *
 * Un destinatario = un riel, y el riel del pago se deriva EXCLUSIVAMENTE de su tipo de
 * cuenta: ni el quote ni el payout lo determinan. Es el punto de decision mas importante
 * del flujo de pagos.
 *
 * Kira no expone actualizacion ni borrado: corregir un destinatario significa crear un
 * reemplazo y archivar el anterior. Por eso el espejo local guarda el registro completo,
 * incluidos los campos que la API acepta y luego no devuelve.
 */
@Getter
public class Recipient {

    private final String id;
    private final TenantId tenantId;
    private final RecipientHolder holder;
    private final RecipientAccount account;
    private final PostalAddress address;
    private Instant createdAt;

    private String kiraRecipientId;
    private RecipientStatus status;
    private String replacedByRecipientId;
    /** Operador que lo registro: no puede aprobar pagos hacia el. Null en los anteriores al 15-sep. */
    private String createdByUserId;
    private Instant updatedAt;

    public Recipient(String id, TenantId tenantId, RecipientHolder holder, RecipientAccount account,
                     PostalAddress address) {
        if (holder == null) {
            throw new DomainException("El destinatario necesita titular.");
        }
        if (account == null) {
            throw new DomainException("El destinatario necesita datos de cobro.");
        }
        // La direccion es obligatoria para bancos; una wallet no la necesita.
        if (account.rail() != Rail.WALLET) {
            if (address == null || address.isBlank()) {
                throw new DomainException("Un destinatario bancario necesita direccion postal.");
            }
            address.assertIso2Country();
        }
        this.id = id;
        this.tenantId = tenantId;
        this.holder = holder;
        this.account = account;
        this.address = address;
        this.status = RecipientStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Recipient rehydrate(String id, TenantId tenantId, RecipientHolder holder,
                                      RecipientAccount account, PostalAddress address,
                                      String kiraRecipientId, RecipientStatus status,
                                      String replacedByRecipientId, Instant createdAt, Instant updatedAt) {
        Recipient r = new Recipient(id, tenantId, holder, account, address);
        // Sin esto, cada lectura "reiniciaba" la fecha de alta con la hora actual.
        r.createdAt = createdAt == null ? r.createdAt : createdAt;
        r.kiraRecipientId = kiraRecipientId;
        r.status = status == null ? RecipientStatus.ACTIVE : status;
        r.replacedByRecipientId = replacedByRecipientId;
        r.updatedAt = updatedAt;
        return r;
    }

    public void recordAuthor(String userId) {
        this.createdByUserId = userId;
    }

    public Rail getRail() {
        return account.rail();
    }

    /** Red de la wallet. Null para rieles bancarios. */
    public String getNetwork() {
        return account instanceof RecipientAccount.Wallet wallet ? wallet.network() : null;
    }

    public String getName() {
        return holder.displayName();
    }

    /** La respuesta usa 'recipient_id', no 'id'. */
    public void linkKiraRecipient(String kiraRecipientId) {
        if (kiraRecipientId == null || kiraRecipientId.isBlank()) {
            throw new DomainException("Kira no devolvio un identificador de destinatario.");
        }
        this.kiraRecipientId = kiraRecipientId;
        touch();
    }

    /**
     * Reemplazo logico: Kira no borra destinatarios, asi que el corregido es otro registro
     * y este solo queda archivado apuntando a su sustituto.
     */
    public void replaceWith(String replacementId) {
        this.replacedByRecipientId = replacementId;
        archive();
    }

    public void archive() {
        this.status = RecipientStatus.ARCHIVED;
        touch();
    }

    public boolean isActive() {
        return status == RecipientStatus.ACTIVE;
    }

    public boolean isRegisteredInKira() {
        return kiraRecipientId != null;
    }

    public void assertUsable() {
        if (!isActive()) {
            throw new DomainException("El destinatario esta archivado y no admite nuevos pagos.");
        }
        if (kiraRecipientId == null) {
            throw new DomainException("El destinatario todavia no esta registrado en Kira.");
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
