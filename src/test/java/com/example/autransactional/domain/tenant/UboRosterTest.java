package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UboRosterTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private Ubo persona(String nombre, boolean propiedad, String porcentaje) {
        Ubo u = new Ubo(nombre, TENANT, nombre, "Perez", new BigDecimal(porcentaje), "Director");
        u.describeRole(propiedad, new BigDecimal(porcentaje), false, false, false, "COL");
        return u;
    }

    @Test
    void elCargoNoConvierteANadieEnBeneficiario() {
        // 'Director' con 40 % pero sin has_ownership no cuenta: Kira mira el booleano.
        Ubo director = persona("Ana", false, "40");

        assertFalse(director.isBeneficialOwner());
        assertThrows(DomainException.class,
                () -> new UboRoster(List.of(director)).assertReadyForVerification());
    }

    @Test
    void pordebajoDelCincoPorCientoNoEsBeneficiario() {
        assertFalse(persona("Luis", true, "4.99").isBeneficialOwner());
        assertTrue(persona("Maria", true, "5").isBeneficialOwner());
    }

    @Test
    void sinBeneficiarioElEnvioSeCortaAntesDeLlamarAKira() {
        var roster = new UboRoster(List.of(persona("Luis", true, "3"), persona("Ana", false, "0")));

        var e = assertThrows(DomainException.class, roster::assertReadyForVerification);
        assertTrue(e.getMessage().contains("5 %"), e.getMessage());
    }

    @Test
    void laSumaDeParticipacionesNoPuedeSuperarCien() {
        var roster = new UboRoster(List.of(persona("Ana", true, "60"), persona("Luis", true, "50")));

        var e = assertThrows(DomainException.class, roster::assertReadyForVerification);
        assertTrue(e.getMessage().contains("100 %"), e.getMessage());
    }

    @Test
    void unGrupoValidoPasa() {
        var roster = new UboRoster(List.of(persona("Ana", true, "60"), persona("Luis", true, "40")));

        assertDoesNotThrow(roster::assertReadyForVerification);
        assertEquals(0, new BigDecimal("100").compareTo(roster.totalOwnership()));
        assertEquals(2, roster.beneficialOwners().size());
    }

    @Test
    void unaEmpresaSinBeneficiariosNoSeEnvia() {
        assertThrows(DomainException.class, () -> new UboRoster(List.of()).assertReadyForVerification());
    }

    @Test
    void elLivenessSoloEstaCompletoCuandoTodosLosBeneficiariosPasan() {
        Ubo ana = persona("Ana", true, "60");
        Ubo luis = persona("Luis", true, "40");
        ana.assignLivenessLink("https://kira/l/1", Instant.now().plusSeconds(3600));
        ana.applyLivenessStatus(LivenessStatus.COMPLETED);

        var roster = new UboRoster(List.of(ana, luis));

        assertFalse(roster.livenessComplete());
        assertEquals(List.of(luis), roster.pendingLiveness());

        luis.applyLivenessStatus(LivenessStatus.COMPLETED);
        assertTrue(new UboRoster(List.of(ana, luis)).livenessComplete());
    }

    @Test
    void elPaisDeNacimientoEsObligatorio() {
        Ubo u = new Ubo("x", TENANT, "Ana", "Perez", new BigDecimal("50"), "Socia");

        assertThrows(DomainException.class,
                () -> u.describeRole(true, new BigDecimal("50"), false, false, false, "  "));
    }

    @Test
    void unResultadoFinalDeLivenessNoRetrocede() {
        Ubo u = persona("Ana", true, "60");
        u.applyLivenessStatus(LivenessStatus.COMPLETED);

        u.applyLivenessStatus(LivenessStatus.PENDING);

        assertEquals(LivenessStatus.COMPLETED, u.getLivenessStatus());
    }
}
