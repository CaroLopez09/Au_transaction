package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.treasury.WalletToken;

import java.util.EnumSet;
import java.util.Set;

/**
 * Parametrizacion por tenant (arquitectura §8): que rieles y tokens tiene habilitados una
 * empresa cliente, segun su acuerdo comercial con Kira, y que modulos completos tiene
 * activados (feature flags, arquitectura §8 tabla de parametros #9).
 *
 * No viene de Kira: es una capacidad interna del portal. Un tenant sin fila propia usa
 * {@link #defaults()} (todo habilitado) para no romper el comportamiento previo a la
 * existencia de este modulo. El umbral de doble aprobacion NO se toca aqui: sigue fijo en
 * {@code bff.payouts.approval} por decision del 15-sep (ver PayoutApprovalPolicy).
 *
 * @param enabledRails    rieles bancarios que este tenant puede usar para pagar
 * @param enabledTokens   stablecoins que este tenant puede usar para destinatarios wallet
 * @param enabledFeatures modulos completos activados para este tenant (liveness, RFIs...)
 */
public record TenantSettings(Set<Rail> enabledRails, Set<WalletToken> enabledTokens,
                             Set<FeatureFlag> enabledFeatures) {

    public TenantSettings {
        enabledRails = enabledRails == null || enabledRails.isEmpty()
                ? EnumSet.allOf(Rail.class) : EnumSet.copyOf(enabledRails);
        enabledTokens = enabledTokens == null || enabledTokens.isEmpty()
                ? EnumSet.allOf(WalletToken.class) : EnumSet.copyOf(enabledTokens);
        enabledFeatures = enabledFeatures == null || enabledFeatures.isEmpty()
                ? EnumSet.allOf(FeatureFlag.class) : EnumSet.copyOf(enabledFeatures);
    }

    /** Conveniencia para cuando no se tocan los feature flags (todos quedan habilitados). */
    public TenantSettings(Set<Rail> enabledRails, Set<WalletToken> enabledTokens) {
        this(enabledRails, enabledTokens, null);
    }

    public static TenantSettings defaults() {
        return new TenantSettings(EnumSet.allOf(Rail.class), EnumSet.allOf(WalletToken.class),
                EnumSet.allOf(FeatureFlag.class));
    }

    public void assertRailEnabled(Rail rail) {
        if (!enabledRails.contains(rail)) {
            throw new DomainException("Tu empresa no tiene habilitado el riel " + rail + ".");
        }
    }

    public void assertTokenEnabled(WalletToken token) {
        if (!enabledTokens.contains(token)) {
            throw new DomainException("Tu empresa no tiene habilitado el token " + token.wireValue() + ".");
        }
    }

    public void assertFeatureEnabled(FeatureFlag feature) {
        if (!enabledFeatures.contains(feature)) {
            throw new DomainException("Tu empresa no tiene habilitado " + feature + ".");
        }
    }
}
