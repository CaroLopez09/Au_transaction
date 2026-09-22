package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountMode;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.account.VirtualAccountStatus;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.application.tenant.KiraUserState;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraNotConfiguredException;
import com.example.autransactional.infrastructure.kira.KiraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Trae al BFF, sin intervencion humana, cada empresa y cuenta que ya exista en el Sandbox de Kira.
 *
 * En este proyecto las empresas se crean SIEMPRE del lado de Kira (sandbox), nunca desde el
 * onboarding del portal. Sin este worker, cada empresa nueva se quedaria invisible para el front
 * hasta que alguien la importara a mano con /api/tenants/import-sandbox. Aqui se hace lo mismo
 * pero de forma periodica y automatica: se lista /v1/users, se descartan los kiraUserId ya
 * conocidos y el resto se da de alta con su cuenta virtual y un administrador ya activo.
 *
 * Solo corre si bff.reconciliation.sandbox-sync-enabled=true (por defecto activo solo en dev,
 * ver application-dev.yaml): crear tenants y administradores automaticamente con contrasenas
 * generadas no es un comportamiento que se deba encender por accidente en cert/prod.
 */
@Component
@ConditionalOnProperty(prefix = "bff.reconciliation", name = "sandbox-sync-enabled", havingValue = "true")
public class SandboxTenantSyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SandboxTenantSyncWorker.class);
    private static final int PAGE_SIZE = 50;
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KiraApiClient kira;
    private final TenantRepository tenants;
    private final VirtualAccountRepository accounts;
    private final OperatorUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final KiraProperties properties;
    private final AuditTrail audit;

    public SandboxTenantSyncWorker(KiraApiClient kira, TenantRepository tenants, VirtualAccountRepository accounts,
                                   OperatorUserRepository users, PasswordEncoder passwordEncoder,
                                   KiraProperties properties, AuditTrail audit) {
        this.kira = kira;
        this.tenants = tenants;
        this.accounts = accounts;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${bff.reconciliation.sandbox-sync-ms:300000}",
            initialDelayString = "${bff.reconciliation.initial-delay-ms:60000}")
    public void sync() {
        List<JsonNode> remoteUsers;
        try {
            remoteUsers = listAllRemoteUsers();
        } catch (KiraNotConfiguredException e) {
            log.warn("Sincronizacion automatica de sandbox omitida: {}", e.getMessage());
            return;
        }

        int imported = 0;
        for (JsonNode remote : remoteUsers) {
            String kiraUserId = text(remote, "id");
            if (kiraUserId == null || kiraUserId.isBlank() || tenants.findByKiraUserId(kiraUserId).isPresent()) {
                continue;
            }
            try {
                importOne(remote, kiraUserId);
                imported++;
            } catch (RuntimeException e) {
                log.error("No se pudo importar automaticamente la empresa {} del sandbox: {}",
                        kiraUserId, e.getMessage(), e);
            }
        }
        if (imported > 0) {
            log.info("Sincronizacion automatica de sandbox: {} empresa(s) nueva(s) dadas de alta.", imported);
        }
    }

    private List<JsonNode> listAllRemoteUsers() {
        List<JsonNode> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode response = kira.listUsers(Map.of("limit", PAGE_SIZE, "offset", offset));
            JsonNode items = response.has("data") ? response.get("data") : response;
            int onThisPage = 0;
            for (JsonNode item : items) {
                all.add(item);
                onThisPage++;
            }
            JsonNode pagination = response.path("pagination");
            boolean hasMore = pagination.has("has_more") && pagination.path("has_more").asBoolean(false);
            if (!hasMore || onThisPage == 0) {
                break;
            }
            offset += PAGE_SIZE;
        }
        return all;
    }

    @Transactional
    public void importOne(JsonNode remote, String kiraUserId) {
        KiraUserState state = KiraUserState.from(remote);
        Tenant tenant = new Tenant(TenantId.of(UUID.randomUUID().toString()), uniqueName(remote, kiraUserId),
                textOrDefault(remote, "tax_id", "PENDIENTE-" + kiraUserId.substring(0, 8)),
                textOrDefault(remote, "formation_country", "Colombia"));
        tenant.linkKiraUser(state.kiraUserId());
        tenant.applyRemoteState(state.status(), state.missingFields(), state.eligibleProducts(),
                state.verificationTriggered());
        tenants.save(tenant);

        String rawPassword = generatePassword();
        String adminEmail = uniqueAdminEmail(remote, kiraUserId);
        OperatorUser administrator = users.create(new OperatorUser(
                UUID.randomUUID().toString(), tenant.getId(), adminEmail, passwordEncoder.encode(rawPassword),
                "Administrador", "Sandbox", Role.ADMIN, UserStatus.ACTIVE, null, false));

        int synchronizedAccounts = synchronizeAccounts(tenant);

        audit.record(null, "tenant.sandbox_auto_imported", "tenant", tenant.getId().value(), null, "OK",
                "kira_user_id=" + kiraUserId + " virtual_accounts=" + synchronizedAccounts
                        + " administrator_id=" + administrator.id());

        // Unica vez que la contrasena generada existe en texto plano: en el log, no en BD ni en auditoria.
        log.warn("Empresa nueva del sandbox importada automaticamente: tenant={} name={} kiraUserId={} "
                        + "admin_email={} admin_password={} (cambiala al primer login)",
                tenant.getId().value(), tenant.getName(), kiraUserId, adminEmail, rawPassword);
    }

    private String uniqueName(JsonNode remote, String kiraUserId) {
        String base = textOrDefault(remote, "business_legal_name", "Empresa sandbox " + kiraUserId.substring(0, 8));
        String candidate = base;
        int suffix = 2;
        while (tenants.existsByNameIgnoreCase(candidate)) {
            candidate = base + " (" + suffix + ")";
            suffix++;
        }
        return candidate;
    }

    private String uniqueAdminEmail(JsonNode remote, String kiraUserId) {
        String base = text(remote, "email");
        String local = "admin+" + kiraUserId.substring(0, 8);
        String domain = (base != null && base.contains("@")) ? base.substring(base.indexOf('@') + 1) : "sandbox.local";
        String candidate = (local + "@" + domain).toLowerCase(Locale.ROOT);
        int suffix = 2;
        while (users.existsByEmail(candidate)) {
            candidate = (local + "-" + suffix + "@" + domain).toLowerCase(Locale.ROOT);
            suffix++;
        }
        return candidate;
    }

    private static String generatePassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private int synchronizeAccounts(Tenant tenant) {
        JsonNode response = kira.listVirtualAccounts(Map.of("user_id", tenant.getKiraUserId()));
        JsonNode items = response.has("data") ? response.get("data") : response;
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
                continue;
            }
            applyRemote(account, kira.getVirtualAccount(kiraAccountId));
            accounts.save(account);
            count++;
        }
        return count;
    }

    private static void applyRemote(VirtualAccount account, JsonNode remoteResponse) {
        JsonNode remote = remoteResponse.has("data") ? remoteResponse.get("data") : remoteResponse;
        account.linkKiraAccount(text(remote, "id"));
        account.describeBank(text(remote, "bank_name"), text(remote, "account_number"),
                text(remote, "routing_number"));
        account.applyRemoteStatus(VirtualAccountStatus.fromWire(text(remote, "status")));
        if (remote.has("available_balance")) {
            account.refreshBalance(remote.path("available_balance").decimalValue(), Instant.now());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }
}
