package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.PostalAddress;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.treasury.BankAccountKind;
import com.example.autransactional.domain.treasury.Recipient;
import com.example.autransactional.domain.treasury.RecipientAccount;
import com.example.autransactional.domain.treasury.RecipientHolder;
import com.example.autransactional.domain.treasury.RecipientRepository;
import com.example.autransactional.domain.treasury.WalletToken;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraResponse;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alta de destinatarios contra POST /v1/recipients.
 *
 * Un destinatario = un riel, y ese riel es el que va a usar cada pago suyo. Kira no ofrece
 * actualizacion ni borrado: corregir uno significa dar de alta un reemplazo y archivar el
 * anterior, asi que aqui no hay un metodo 'update'.
 */
@Service
public class RegisterRecipientService {

    private static final Logger log = LoggerFactory.getLogger(RegisterRecipientService.class);

    private final RecipientRepository recipients;
    private final TenantRepository tenants;
    private final KiraApiClient kira;
    private final AuditTrail audit;

    public RegisterRecipientService(RecipientRepository recipients, TenantRepository tenants,
                                    KiraApiClient kira, AuditTrail audit) {
        this.recipients = recipients;
        this.tenants = tenants;
        this.kira = kira;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<RecipientView> list(AuthenticatedOperator operator) {
        return recipients.findActiveByTenant(operator.tenantId()).stream()
                .map(RecipientView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecipientView get(AuthenticatedOperator operator, String recipientId) {
        return RecipientView.from(load(operator.tenantId(), recipientId));
    }

    @Transactional
    public RecipientView register(AuthenticatedOperator operator,
                                  RecipientCommands.RegisterRecipient command) {
        if (!operator.role().canCreatePayout()) {
            throw new DomainException("Tu rol no puede registrar destinatarios.");
        }
        Tenant tenant = tenants.findById(operator.tenantId())
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
        // Sin user verificado en Kira no hay a quien colgar el destinatario.
        tenant.assertCanOperateTreasury();
        tenant.assertRegisteredInKira();

        Recipient recipient = new Recipient(
                UUID.randomUUID().toString(),
                operator.tenantId(),
                buildHolder(command),
                buildAccount(command),
                toAddress(command.address()));

        // Una clave por intencion: el reintento devuelve el mismo destinatario, no otro.
        IdempotencyKey key = IdempotencyKey.newKey();
        KiraResponse response = kira.createRecipient(buildBody(tenant, recipient, command), key);
        JsonNode data = response.data();

        // La respuesta usa recipient_id, no id.
        String kiraRecipientId = data.path("recipient_id").asText(null);
        if (kiraRecipientId == null) {
            kiraRecipientId = data.path("id").asText(null);
        }
        recipient.linkKiraRecipient(kiraRecipientId);
        recipients.save(recipient);

        if (response.alreadyExisted()) {
            // 202: ya existia y Kira devuelve el registro existente. Es exito.
            log.info("El destinatario {} ya estaba registrado en Kira como {}.",
                    recipient.getId(), kiraRecipientId);
        }
        audit.record(operator, "recipient.registered", "recipient", recipient.getId(), key.value(),
                "OK", "kira_recipient_id=" + kiraRecipientId + " riel=" + recipient.getRail()
                        + (response.alreadyExisted() ? " (ya existia)" : ""));

        return RecipientView.from(recipient, response.alreadyExisted());
    }

    /**
     * Archiva un destinatario. Es un reemplazo logico: Kira no borra, asi que el registro
     * remoto sigue existiendo y lo que cambia es que aqui deja de ofrecerse para pagos.
     */
    @Transactional
    public RecipientView archive(AuthenticatedOperator operator, String recipientId,
                                 RecipientCommands.ArchiveRecipient command) {
        if (!operator.role().canCreatePayout()) {
            throw new DomainException("Tu rol no puede archivar destinatarios.");
        }
        Recipient recipient = load(operator.tenantId(), recipientId);

        if (command != null && command.replacedByRecipientId() != null
                && !command.replacedByRecipientId().isBlank()) {
            // El reemplazo debe existir y ser de la misma organizacion.
            load(operator.tenantId(), command.replacedByRecipientId());
            recipient.replaceWith(command.replacedByRecipientId());
        } else {
            recipient.archive();
        }
        recipients.save(recipient);

        audit.record(operator, "recipient.archived", "recipient", recipient.getId(), null, "OK",
                recipient.getReplacedByRecipientId() == null ? null
                        : "reemplazado_por=" + recipient.getReplacedByRecipientId());
        return RecipientView.from(recipient);
    }

    private RecipientHolder buildHolder(RecipientCommands.RegisterRecipient command) {
        return command.business()
                ? RecipientHolder.company(command.companyName(), command.email(), command.phone())
                : RecipientHolder.person(command.firstName(), command.lastName(),
                command.email(), command.phone());
    }

    private RecipientAccount buildAccount(RecipientCommands.RegisterRecipient command) {
        Rail rail = Rail.from(command.rail());
        return switch (rail) {
            case ACH -> new RecipientAccount.Ach(command.routingNumber(), command.accountNumber(),
                    BankAccountKind.from(command.accountKind()), command.bankName(),
                    command.bankAddressText(), command.docType(), command.docNumber());
            case WIRE -> new RecipientAccount.Wire(command.routingNumber(), command.swiftCode(),
                    command.accountNumber(), BankAccountKind.from(command.accountKind()),
                    command.bankName(), toAddress(command.bankAddress()),
                    command.docType(), command.docNumber());
            case WALLET -> new RecipientAccount.Wallet(WalletToken.from(command.token()),
                    command.network(), command.walletAddress(),
                    command.docType(), command.docNumber());
        };
    }

    /** Cuerpo de POST /v1/recipients: 'account' es un oneOf discriminado por account_type. */
    private Map<String, Object> buildBody(Tenant tenant, Recipient recipient,
                                          RecipientCommands.RegisterRecipient command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", tenant.getKiraUserId());
        body.put("type", recipient.getHolder().wireType());

        RecipientHolder holder = recipient.getHolder();
        // No existe holder_name: el titular se infiere de estos campos.
        putIfPresent(body, "first_name", holder.firstName());
        putIfPresent(body, "last_name", holder.lastName());
        putIfPresent(body, "company_name", holder.companyName());
        putIfPresent(body, "email", holder.email());
        putIfPresent(body, "phone", holder.phone());

        if (recipient.getAddress() != null) {
            body.put("address", addressBody(recipient.getAddress()));
        }
        body.put("account", accountBody(recipient.getAccount()));
        return body;
    }

    private Map<String, Object> accountBody(RecipientAccount account) {
        Map<String, Object> node = new LinkedHashMap<>();
        switch (account) {
            case RecipientAccount.Ach ach -> {
                node.put("account_type", "ACH");
                node.put("routing_number", ach.routingNumber());
                node.put("account_number", ach.accountNumber());
                node.put("type", ach.kind().wireValue());
                putIfPresent(node, "bank_name", ach.bankName());
                // En ACH la direccion del banco es texto plano, no objeto.
                putIfPresent(node, "bank_address", ach.bankAddressText());
            }
            case RecipientAccount.Wire wire -> {
                node.put("account_type", "WIRE");
                node.put("routing_number", wire.routingNumber());
                putIfPresent(node, "swift_code", wire.swiftCode());
                node.put("account_number", wire.accountNumber());
                node.put("type", wire.kind().wireValue());
                putIfPresent(node, "bank_name", wire.bankName());
                // En WIRE es un objeto. Kira devolvera state y postal_code vacios.
                if (wire.bankAddress() != null) {
                    node.put("bank_address", addressBody(wire.bankAddress()));
                }
            }
            case RecipientAccount.Wallet wallet -> {
                node.put("account_type", "WALLET");
                node.put("token", wallet.token().wireValue());
                node.put("network", wallet.network());
                node.put("address", wallet.address());
            }
        }
        putIfPresent(node, "doc_type", account.docType());
        putIfPresent(node, "doc_number", account.docNumber());
        return node;
    }

    private Map<String, Object> addressBody(PostalAddress address) {
        Map<String, Object> node = new LinkedHashMap<>();
        putIfPresent(node, "street_name", address.streetName());
        putIfPresent(node, "city", address.city());
        putIfPresent(node, "state", address.state());
        putIfPresent(node, "postal_code", address.postalCode());
        putIfPresent(node, "country", address.country());
        return node;
    }

    private static PostalAddress toAddress(RecipientCommands.Address address) {
        return address == null ? null : new PostalAddress(address.streetName(), address.city(),
                address.state(), address.postalCode(), address.country());
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private Recipient load(TenantId tenantId, String recipientId) {
        return recipients.findByIdAndTenant(recipientId, tenantId)
                .orElseThrow(() -> new DomainException("Destinatario no encontrado."));
    }
}
