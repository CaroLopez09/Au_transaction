package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.treasury.WalletToken;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantSettingsTest {

    @Test
    void porDefectoTodoQuedaHabilitado() {
        TenantSettings settings = TenantSettings.defaults();

        assertEquals(EnumSet.allOf(Rail.class), settings.enabledRails());
        assertEquals(EnumSet.allOf(WalletToken.class), settings.enabledTokens());
        assertEquals(EnumSet.allOf(FeatureFlag.class), settings.enabledFeatures());
    }

    @Test
    void unConjuntoVacioSeNormalizaATodoHabilitado() {
        TenantSettings settings = new TenantSettings(Set.of(), Set.of(), Set.of());

        assertEquals(EnumSet.allOf(Rail.class), settings.enabledRails());
        assertEquals(EnumSet.allOf(WalletToken.class), settings.enabledTokens());
        assertEquals(EnumSet.allOf(FeatureFlag.class), settings.enabledFeatures());
    }

    @Test
    void unRielNoHabilitadoFalla() {
        TenantSettings settings = new TenantSettings(Set.of(Rail.ACH), Set.of(WalletToken.USDC));

        assertThrows(DomainException.class, () -> settings.assertRailEnabled(Rail.WIRE));
        assertDoesNotThrow(() -> settings.assertRailEnabled(Rail.ACH));
    }

    @Test
    void unTokenNoHabilitadoFalla() {
        TenantSettings settings = new TenantSettings(Set.of(Rail.WALLET), Set.of(WalletToken.USDT));

        assertThrows(DomainException.class, () -> settings.assertTokenEnabled(WalletToken.USDC));
        assertDoesNotThrow(() -> settings.assertTokenEnabled(WalletToken.USDT));
    }

    @Test
    void unFeatureFlagNoHabilitadoFalla() {
        TenantSettings settings = new TenantSettings(EnumSet.allOf(Rail.class),
                EnumSet.allOf(WalletToken.class), Set.of(FeatureFlag.RFIS));

        assertThrows(DomainException.class, () -> settings.assertFeatureEnabled(FeatureFlag.LIVENESS));
        assertDoesNotThrow(() -> settings.assertFeatureEnabled(FeatureFlag.RFIS));
    }
}
