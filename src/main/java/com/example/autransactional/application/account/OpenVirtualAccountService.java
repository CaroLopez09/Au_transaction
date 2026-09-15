package com.example.autransactional.application.account;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountMode;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.account.VirtualAccountStatus;
import com.example.autransactional.application.shared.IdempotencyKeyStore;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraApiException;
import com.example.autransactional.infrastructure.kira.KiraProperties;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Apertura y seguimiento de cuentas virtuales.
 *
 * Abrir una cuenta exige que el KYB este VERIFIED Y que el producto este elegible: son dos
 * condiciones, no una. Y una vez abierta, 'aprobada' no significa que pueda mover fondos:
 * eso lo dice un numero de cuenta real o el evento virtual_account.activated.
 */
@Service
public class OpenVirtualAccountService {

    private static final Logger log = LoggerFactory.getLogger(OpenVirtualAccountService.class);

    /** Unico valor admitido por el campo 'type'. */
    private static final String ACCOUNT_TYPE = "US_BANK";

    private final VirtualAccountRepository accounts;
    private final TenantRepository tenants;
    private final KiraApiClient kira;
    private final KiraProperties properties;
    private final AuditTrail audit;
    private final IdempotencyKeyStore idempotencyKeys;

    public OpenVirtualAccountService(VirtualAccountRepository accounts, TenantRepository tenants,
                                     KiraApiClient kira, KiraProperties properties, AuditTrail audit,
                                     IdempotencyKeyStore idempotencyKeys) {
        this.accounts = accounts;
        this.tenants = tenants;
        this.kira = kira;
        this.properties = properties;
        this.audit = audit;
        this.idempotencyKeys = idempotencyKeys;
    }

    @Transactional(readOnly = true)
    public List<VirtualAccountView> list(AuthenticatedOperator operator) {
        return accounts.findByTenant(operator.tenantId()).stream()
                .map(VirtualAccountView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public VirtualAccountView get(AuthenticatedOperator operator, String accountId) {
        return VirtualAccountView.from(load(operator.tenantId(), accountId));
    }

    @Transactional
    public VirtualAccountView open(AuthenticatedOperator operator,
                                   VirtualAccountCommands.OpenAccount command) {
        if (!operator.role().canCreatePayout() && !operator.role().canManageCompliance()) {
            throw new DomainException("Tu rol no puede abrir cuentas virtuales.");
        }
        Tenant tenant = tenants.findById(operator.tenantId())
                .orElseThrow(() -> new DomainException("La organizacion no existe."));

        // Dos condiciones, no una: KYB aprobado Y producto elegible sin campos pendientes.
        if (!tenant.isReadyFor(EligibleProduct.USA_VIRTUAL_ACCOUNTS)) {
            throw new DomainException("La empresa todavia no puede abrir cuentas: revisa el estado del "
                    + "KYB y los campos pendientes del producto.");
        }

        VirtualAccount account = new VirtualAccount(
                UUID.randomUUID().toString(),
                operator.tenantId(),
                command.currency() == null || command.currency().isBlank() ? "USD" : command.currency(),
                VirtualAccountMode.from(command.mode()),
                properties.bank(),
                command.description());

        IdempotencyKey key = account.reserveOpeningKey();
        // En transaccion propia: si Kira falla, el rollback de este metodo no puede borrar la clave.
        idempotencyKeys.persistNow(account);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", tenant.getKiraUserId());
        body.put("type", ACCOUNT_TYPE);
        // El banco viene de la configuracion: 'portage' en sandbox da 400 "Invalid bank".
        body.put("bank", account.getBank());
        body.put("mode", account.getMode().wireValue());
        if (account.getDescription() != null && !account.getDescription().isBlank()) {
            body.put("description", account.getDescription());
        }

        try {
            applyRemote(account, kira.createVirtualAccount(body, key));
        } catch (KiraApiException e) {
            if (e.getStatusCode() == 409) {
                // Ya existe una cuenta para este user: se reutiliza, no es un error.
                log.info("Kira ya tenia una cuenta virtual para {}; se reutiliza.", tenant.getKiraUserId());
                return VirtualAccountView.from(adoptExisting(operator, tenant, account));
            }
            audit.record(operator, "virtual_account.opened", "virtual_account", account.getId(),
                    key.value(), "ERROR", e.getMessage());
            throw e;
        }

        accounts.save(account);
        audit.record(operator, "virtual_account.opened", "virtual_account", account.getId(),
                key.value(), "OK", "kira_account_id=" + account.getKiraAccountId());
        return VirtualAccountView.from(account);
    }

    /** El recurso es la autoridad. Tambien cubre el hueco de un evento de activacion perdido. */
    @Transactional
    public VirtualAccountView refresh(AuthenticatedOperator operator, String accountId) {
        VirtualAccount account = load(operator.tenantId(), accountId);
        assertOpen(account);
        applyRemote(account, kira.getVirtualAccount(account.getKiraAccountId()));
        accounts.save(account);

        if (account.isActivationDelayed(Instant.now())) {
            log.warn("La cuenta virtual {} lleva mas de {} sin activarse.",
                    account.getId(), VirtualAccount.ACTIVATION_GRACE);
        }
        return VirtualAccountView.from(account);
    }

    /**
     * Relee la cuenta sin operador, para el worker de reconciliacion: failed y deactivated no
     * tienen webhook propio y frozen tampoco, asi que solo se ven consultando el recurso.
     * Devuelve true si cambio el estado o la disponibilidad de fondos.
     */
    @Transactional
    public boolean reconcile(VirtualAccount account) {
        if (!account.isOpenInKira()) {
            return false;
        }
        VirtualAccountStatus antes = account.getStatus();
        boolean fondosAntes = account.isFundsReady();
        applyRemote(account, kira.getVirtualAccount(account.getKiraAccountId()));
        accounts.save(account);
        return antes != account.getStatus() || fondosAntes != account.isFundsReady();
    }

    /**
     * Refresca el saldo.
     *
     * Durante la activacion, GET /balance puede responder 400: eso no es un fallo sino
     * "todavia calculando", y se devuelve el ultimo saldo conocido en lugar de un error.
     */
    @Transactional
    public VirtualAccountView refreshBalance(AuthenticatedOperator operator, String accountId) {
        VirtualAccount account = load(operator.tenantId(), accountId);
        assertOpen(account);
        try {
            JsonNode response = kira.getVirtualAccountBalance(account.getKiraAccountId());
            JsonNode data = response.has("data") ? response.get("data") : response;
            // Aqui el saldo llega como decimal, no en unidades menores como en las cotizaciones.
            if (data.has("available_balance")) {
                account.refreshBalance(data.path("available_balance").decimalValue(), Instant.now());
                accounts.save(account);
            }
        } catch (KiraApiException e) {
            if (e.getStatusCode() != 400) {
                throw e;
            }
            log.info("Saldo de {} aun no disponible: la cuenta sigue activandose.", account.getId());
        }
        return VirtualAccountView.from(account);
    }

    /** Simulacion de deposito. Solo existe en el sandbox; en produccion Kira responde 403. */
    @Transactional
    public VirtualAccountView simulateDeposit(AuthenticatedOperator operator, String accountId,
                                              VirtualAccountCommands.SimulateDeposit command) {
        if (!properties.sandbox()) {
            throw new DomainException("Simular depositos solo es posible en el entorno de pruebas.");
        }
        VirtualAccount account = load(operator.tenantId(), accountId);
        assertOpen(account);

        Map<String, Object> body = Map.of(
                "amount", command.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                "payment_type", command.paymentType() == null ? "wire" : command.paymentType());
        kira.simulateDeposit(account.getKiraAccountId(), body);

        audit.record(operator, "virtual_account.deposit_simulated", "virtual_account", account.getId(),
                null, "OK", "monto=" + command.amount());
        return refreshBalance(operator, accountId);
    }

    /**
     * Adopta la cuenta que Kira ya tenia abierta para esta empresa.
     * El listado devuelve provider y currency nulos, asi que se relee la cuenta individual.
     */
    private VirtualAccount adoptExisting(AuthenticatedOperator operator, Tenant tenant,
                                         VirtualAccount account) {
        JsonNode listado = kira.listVirtualAccounts(Map.of("user_id", tenant.getKiraUserId()));
        JsonNode items = listado.has("data") ? listado.get("data") : listado;

        String remoteId = null;
        for (JsonNode item : items.isArray() ? items : items.path("items")) {
            remoteId = item.path("id").asText(null);
            if (remoteId != null) {
                break;
            }
        }
        if (remoteId == null) {
            throw new DomainException("Kira indica que ya existe una cuenta, pero no la devuelve.");
        }
        applyRemote(account, kira.getVirtualAccount(remoteId));
        accounts.save(account);

        audit.record(operator, "virtual_account.opened", "virtual_account", account.getId(),
                account.getOpeningIdempotencyKey(), "OK", "reutilizada kira_account_id=" + remoteId);
        return account;
    }

    private void applyRemote(VirtualAccount account, JsonNode response) {
        JsonNode data = response.has("data") ? response.get("data") : response;

        String remoteId = text(data, "id");
        if (remoteId != null) {
            account.linkKiraAccount(remoteId);
        }
        account.describeBank(text(data, "bank_name"), text(data, "account_number"),
                text(data, "routing_number"));
        account.applyRemoteStatus(VirtualAccountStatus.fromWire(text(data, "status")));

        BigDecimal balance = data.has("available_balance")
                ? data.path("available_balance").decimalValue() : null;
        account.refreshBalance(balance, Instant.now());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void assertOpen(VirtualAccount account) {
        if (!account.isOpenInKira()) {
            throw new DomainException("La cuenta virtual todavia no esta abierta en Kira.");
        }
    }

    private VirtualAccount load(TenantId tenantId, String accountId) {
        return accounts.findByIdAndTenant(accountId, tenantId)
                .orElseThrow(() -> new DomainException("Cuenta virtual no encontrada."));
    }
}
