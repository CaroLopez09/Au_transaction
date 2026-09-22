package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountMode;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.account.VirtualAccountStatus;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraProperties;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Importa al BFF una empresa y sus cuentas ya existentes en el Sandbox de Kira. */
@Service
public class ImportSandboxTenantService {

    private final TenantRepository tenants;
    private final VirtualAccountRepository accounts;
    private final OperatorUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final KiraApiClient kira;
    private final KiraProperties properties;
    private final AuditTrail audit;

    public ImportSandboxTenantService(TenantRepository tenants, VirtualAccountRepository accounts,
                                      OperatorUserRepository users, PasswordEncoder passwordEncoder,
                                      KiraApiClient kira, KiraProperties properties, AuditTrail audit) {
        this.tenants = tenants;
        this.accounts = accounts;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.kira = kira;
        this.properties = properties;
        this.audit = audit;
    }

    @Transactional
    public ImportView importTenant(AuthenticatedOperator operator, ImportCommand command) {
        if (operator.role() != Role.PLATFORM_OPERATOR) {
            throw new DomainException("Tu rol no puede importar empresas desde Kira.");
        }

        JsonNode remoteUser = data(kira.getUser(command.kiraUserId()));
        KiraUserState state = KiraUserState.from(remoteUser);
        if (state.kiraUserId() == null || state.kiraUserId().isBlank()) {
            throw new DomainException("Kira no devolvio un identificador de empresa valido.");
        }

        Tenant tenant = resolveTenant(operator, command, state.kiraUserId());
        tenant.linkKiraUser(state.kiraUserId());
        tenant.applyRemoteState(state.status(), state.missingFields(), state.eligibleProducts(),
                state.verificationTriggered());
        tenants.save(tenant);
        String administratorId = provisionInitialAdministrator(operator, tenant, command.administrator());

        int synchronizedAccounts = synchronizeAccounts(tenant);
        audit.record(operator, "tenant.sandbox_imported", "tenant", tenant.getId().value(), null, "OK",
                "kira_user_id=" + state.kiraUserId() + " virtual_accounts=" + synchronizedAccounts
                        + " administrator_id=" + administratorId);
        return new ImportView(OnboardingView.from(tenant), synchronizedAccounts, administratorId);
    }

    private String provisionInitialAdministrator(AuthenticatedOperator operator, Tenant tenant,
                                                 InitialAdministrator command) {
        if (operator.role() != Role.PLATFORM_OPERATOR) {
            throw new DomainException("Solo Operaciones AU puede crear el administrador inicial.");
        }
        if (users.findByTenant(tenant.getId()).stream().anyMatch(user -> user.role() == Role.ADMIN)) {
            throw new DomainException("La empresa ya tiene un administrador local.");
        }
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new DomainException("Ya existe un usuario con ese correo.");
        }
        OperatorUser administrator = users.create(new OperatorUser(
                UUID.randomUUID().toString(), tenant.getId(), email, passwordEncoder.encode(command.password()),
                command.firstName().trim(), command.lastName().trim(), Role.ADMIN,
                UserStatus.PENDING_IDENTITY, null, false));
        return administrator.id();
    }

    private Tenant resolveTenant(AuthenticatedOperator operator, ImportCommand command, String kiraUserId) {
        return tenants.findByKiraUserId(kiraUserId)
                .orElseGet(() -> new Tenant(TenantId.of(UUID.randomUUID().toString()), command.name(),
                        command.taxId(), "Colombia"));
    }

    private int synchronizeAccounts(Tenant tenant) {
        JsonNode response = kira.listVirtualAccounts(java.util.Map.of("user_id", tenant.getKiraUserId()));
        JsonNode items = data(response);
        if (!items.isArray()) {
            items = items.path("items");
        }

        int count = 0;
        for (JsonNode item : items) {
            String kiraAccountId = text(item, "id");
            if (kiraAccountId == null || kiraAccountId.isBlank()) {
                continue;
            }
            VirtualAccount account = accounts.findByKiraAccountId(kiraAccountId)
                    .orElseGet(() -> new VirtualAccount(UUID.randomUUID().toString(), tenant.getId(),
                            textOrDefault(item, "currency", "USD"),
                            VirtualAccountMode.from(text(item, "mode")), properties.bank(),
                            text(item, "description")));
            if (!account.getTenantId().equals(tenant.getId())) {
                throw new DomainException("La cuenta de Kira ya esta asociada a otra empresa.");
            }
            applyRemote(account, data(kira.getVirtualAccount(kiraAccountId)));
            accounts.save(account);
            count++;
        }
        return count;
    }

    private static void applyRemote(VirtualAccount account, JsonNode remote) {
        account.linkKiraAccount(text(remote, "id"));
        account.describeBank(text(remote, "bank_name"), text(remote, "account_number"),
                text(remote, "routing_number"));
        account.applyRemoteStatus(VirtualAccountStatus.fromWire(text(remote, "status")));
        if (remote.has("available_balance")) {
            account.refreshBalance(remote.path("available_balance").decimalValue(), Instant.now());
        }
    }

    private static JsonNode data(JsonNode response) {
        return response.has("data") ? response.get("data") : response;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    public record ImportCommand(String kiraUserId, String name, String taxId, InitialAdministrator administrator) {
        public ImportCommand {
            if (kiraUserId == null || kiraUserId.isBlank() || name == null || name.isBlank() || administrator == null) {
                throw new DomainException("kiraUserId, name y administrator son obligatorios.");
            }
        }
    }

    public record InitialAdministrator(String email, String firstName, String lastName, String password) {
    }

    public record ImportView(OnboardingView tenant, int synchronizedAccounts, String administratorId) {
    }
}