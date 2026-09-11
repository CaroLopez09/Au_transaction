package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantOnboardingTest {

    private Tenant empresa() {
        return new Tenant(TenantId.of("juriscop"), "Juriscop", "900123456-1", "Colombia");
    }

    @Test
    void laClaveDeIdempotenciaSeReservaUnaSolaVez() {
        // Un reintento del alta debe reutilizar la misma clave, o crearia dos empresas en Kira.
        Tenant t = empresa();

        var primera = t.reserveOnboardingKey();
        var segunda = t.reserveOnboardingKey();

        assertEquals(primera, segunda);
    }

    @Test
    void elAltaNoDisparaLaVerificacion() {
        // El 201 de POST /v1/users devuelve CREATED: la verificacion no arranca sola.
        Tenant t = empresa();

        t.linkKiraUser("usr_123");

        assertEquals(TenantStatus.CREATED, t.getStatus());
        assertFalse(t.isVerificationTriggered());
        assertTrue(t.isRegisteredInKira());
    }

    @Test
    void noSeReasignaLaEmpresaAOtroUsuarioDeKira() {
        Tenant t = empresa();
        t.linkKiraUser("usr_123");

        assertThrows(DomainException.class, () -> t.linkKiraUser("usr_999"));
    }

    @Test
    void losCamposPendientesSonLosGeneralesMasLosDelProducto() {
        Tenant t = empresa();

        t.applyRemoteState(TenantStatus.CREATED, new MissingFields(Map.of(
                        MissingFields.GENERAL, List.of("business_type"),
                        EligibleProduct.USA_VIRTUAL_ACCOUNTS, List.of("expected_monthly_volume"))),
                List.of(), null);

        assertEquals(List.of("business_type", "expected_monthly_volume"),
                t.getMissingFields().forProduct(EligibleProduct.USA_VIRTUAL_ACCOUNTS));
        assertFalse(t.isReadyFor(EligibleProduct.USA_VIRTUAL_ACCOUNTS));
    }

    @Test
    void verifiedNoBastaSiElProductoNoEsElegible() {
        // Abrir cuenta virtual exige las dos condiciones a la vez.
        Tenant t = empresa();
        t.linkKiraUser("usr_123");

        t.applyRemoteState(TenantStatus.VERIFIED, MissingFields.empty(),
                List.of(new EligibleProduct(EligibleProduct.USA_VIRTUAL_ACCOUNTS, false, List.of(),
                        EligibleProduct.EDD_REQUIRED)), true);

        assertTrue(t.isVerified());
        assertFalse(t.isReadyFor(EligibleProduct.USA_VIRTUAL_ACCOUNTS));
        assertTrue(t.product(EligibleProduct.USA_VIRTUAL_ACCOUNTS).orElseThrow()
                .requiresEnhancedDueDiligence());
    }

    @Test
    void conKybAprobadoYProductoElegibleSiEstaLista() {
        Tenant t = empresa();
        t.linkKiraUser("usr_123");

        t.applyRemoteState(TenantStatus.VERIFIED, MissingFields.empty(),
                List.of(new EligibleProduct(EligibleProduct.USA_VIRTUAL_ACCOUNTS, true, List.of(), null)), true);

        assertTrue(t.isReadyFor(EligibleProduct.USA_VIRTUAL_ACCOUNTS));
        assertDoesNotThrow(t::assertCanOperateTreasury);
    }

    @Test
    void laVerificacionDisparadaNoSeRevierteSiElGetNoLaReporta() {
        // El GET no devuelve verification_triggered: leerlo como false borraria el dato.
        Tenant t = empresa();
        t.linkKiraUser("usr_123");
        t.applyRemoteState(TenantStatus.VERIFYING, MissingFields.empty(), List.of(), true);

        t.applyRemoteState(TenantStatus.VERIFYING, MissingFields.empty(), List.of(), null);

        assertTrue(t.isVerificationTriggered());
    }

    @Test
    void sinVerificacionDisparadaNoSePidenEnlacesDeLiveness() {
        // Kira responde 422 "No verification is in progress"; se corta antes de gastar la llamada.
        Tenant t = empresa();
        t.linkKiraUser("usr_123");

        var e = assertThrows(DomainException.class, t::assertVerificationInProgress);
        assertTrue(e.getMessage().contains("verificacion"), e.getMessage());
    }

    @Test
    void unaEmpresaSinKybNoOperaTesoreria() {
        Tenant t = empresa();

        assertThrows(DomainException.class, t::assertCanOperateTreasury);
    }
}
