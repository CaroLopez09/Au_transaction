package com.example.autransactional.application.platform;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.FeatureFlag;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantSettings;
import com.example.autransactional.domain.treasury.WalletToken;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Modulo de parametrizacion (arquitectura §8): que rieles, tokens y modulos completos (feature
 * flags) tiene habilitados cada empresa cliente. Solo PLATFORM_OPERATOR puede tocarlo (lo aplica
 * el @PreAuthorize del controlador); esta clase asume que ya paso ese filtro.
 */
@Service
public class TenantSettingsService {

    private final TenantRepository tenants;
    private final AuditTrail audit;

    public TenantSettingsService(TenantRepository tenants, AuditTrail audit) {
        this.tenants = tenants;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public TenantSettingsView get(AuthenticatedOperator operator, String tenantId) {
        Tenant tenant = load(tenantId);
        return TenantSettingsView.from(tenant);
    }

    @Transactional
    public TenantSettingsView update(AuthenticatedOperator operator, String tenantId,
                                     Set<String> rails, Set<String> tokens, Set<String> features) {
        Tenant tenant = load(tenantId);
        TenantSettings settings = new TenantSettings(toRails(rails), toTokens(tokens), toFeatures(features));
        tenant.applySettings(settings);
        tenants.save(tenant);
        audit.record(operator, "tenant.settings_updated", "tenant", tenant.getId().value(), null, "OK",
                "rieles=" + settings.enabledRails() + " tokens=" + settings.enabledTokens()
                        + " features=" + settings.enabledFeatures());
        return TenantSettingsView.from(tenant);
    }

    private Tenant load(String tenantId) {
        return tenants.findById(TenantId.of(tenantId))
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
    }

    private Set<Rail> toRails(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EnumSet.allOf(Rail.class);
        }
        Set<Rail> rails = EnumSet.noneOf(Rail.class);
        raw.forEach(value -> rails.add(Rail.from(value)));
        return rails;
    }

    private Set<WalletToken> toTokens(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EnumSet.allOf(WalletToken.class);
        }
        Set<WalletToken> tokens = EnumSet.noneOf(WalletToken.class);
        raw.forEach(value -> tokens.add(WalletToken.from(value)));
        return tokens;
    }

    private Set<FeatureFlag> toFeatures(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EnumSet.allOf(FeatureFlag.class);
        }
        Set<FeatureFlag> features = EnumSet.noneOf(FeatureFlag.class);
        raw.forEach(value -> features.add(FeatureFlag.from(value)));
        return features;
    }

    /** Proyeccion de lectura: nombres habilitados y catalogo completo disponible de cada tipo. */
    public record TenantSettingsView(String tenantId, List<String> enabledRails, List<String> enabledTokens,
                                     List<String> enabledFeatures, List<String> availableRails,
                                     List<String> availableTokens, List<String> availableFeatures) {

        static TenantSettingsView from(Tenant tenant) {
            TenantSettings settings = tenant.getSettings();
            return new TenantSettingsView(tenant.getId().value(),
                    settings.enabledRails().stream().map(Enum::name).sorted().toList(),
                    settings.enabledTokens().stream().map(Enum::name).sorted().toList(),
                    settings.enabledFeatures().stream().map(Enum::name).sorted().toList(),
                    List.of(Rail.ACH.name(), Rail.WIRE.name(), Rail.WALLET.name()),
                    List.of(WalletToken.USDC.name(), WalletToken.USDT.name(), WalletToken.COPM.name()),
                    List.of(FeatureFlag.LIVENESS.name(), FeatureFlag.RFIS.name()));
        }
    }
}
