package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.PostalAddress;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Un destinatario = un riel, y de ese riel sale el riel de todos sus pagos. Las
 * combinaciones imposibles se cortan al construirlo, no al pagar.
 */
class RecipientAccountTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private PostalAddress direccion() {
        return new PostalAddress("1 Main St", "New York", "NY", "10001", "US");
    }

    @Test
    void elRoutingNumberTieneNueveDigitos() {
        assertThrows(DomainException.class, () -> new RecipientAccount.Ach(
                "12345", "1234567890", BankAccountKind.CHECKING, "Bank", null, "ein", "12-3"));
        assertThrows(DomainException.class, () -> new RecipientAccount.Ach(
                "02100002A", "1234567890", BankAccountKind.CHECKING, "Bank", null, "ein", "12-3"));
        assertDoesNotThrow(() -> new RecipientAccount.Ach(
                "021000021", "1234567890", BankAccountKind.CHECKING, "Bank", null, "ein", "12-3"));
    }

    @Test
    void elSwiftTieneOchoUOnceCaracteres() {
        assertThrows(DomainException.class, () -> new RecipientAccount.Wire(
                "021000021", "EXAM", "1234567890", BankAccountKind.CHECKING, "Bank",
                direccion(), "ein", "12-3"));
        assertDoesNotThrow(() -> new RecipientAccount.Wire(
                "021000021", "EXAMUS33", "1234567890", BankAccountKind.CHECKING, "Bank",
                direccion(), "ein", "12-3"));
        assertDoesNotThrow(() -> new RecipientAccount.Wire(
                "021000021", "EXAMUS33XXX", "1234567890", BankAccountKind.CHECKING, "Bank",
                direccion(), "ein", "12-3"));
    }

    @Test
    void usdcNoExisteEnTron() {
        var e = assertThrows(DomainException.class,
                () -> new RecipientAccount.Wallet(WalletToken.USDC, "tron", "0xabc", "passport", "AB1"));

        assertTrue(e.getMessage().contains("polygon"), e.getMessage());
    }

    @Test
    void usdtSiExisteEnTron() {
        assertDoesNotThrow(
                () -> new RecipientAccount.Wallet(WalletToken.USDT, "tron", "T9y", "passport", "AB1"));
    }

    @Test
    void copmSoloVivEnPolygon() {
        assertDoesNotThrow(
                () -> new RecipientAccount.Wallet(WalletToken.COPM, "polygon", "0xabc", null, null));
        assertThrows(DomainException.class,
                () -> new RecipientAccount.Wallet(WalletToken.COPM, "solana", "0xabc", null, null));
    }

    @Test
    void elRielSaleDelTipoDeCuenta() {
        assertEquals(Rail.ACH, new RecipientAccount.Ach("021000021", "1", BankAccountKind.SAVINGS,
                "Bank", null, null, null).rail());
        assertEquals(Rail.WALLET, new RecipientAccount.Wallet(WalletToken.USDC, "polygon", "0x",
                null, null).rail());
    }

    @Test
    void unaEmpresaNecesitaRazonSocialYUnaPersonaNombreCompleto() {
        assertThrows(DomainException.class, () -> RecipientHolder.company("  ", null, null));
        assertThrows(DomainException.class, () -> RecipientHolder.person("Ana", null, null, null));
        assertEquals("Acme S.A.S.", RecipientHolder.company("Acme S.A.S.", null, null).displayName());
        assertEquals("Ana Perez", RecipientHolder.person("Ana", "Perez", null, null).displayName());
    }

    @Test
    void elTelefonoTieneTope() {
        assertThrows(DomainException.class,
                () -> RecipientHolder.person("Ana", "Perez", null, "12345678901234567"));
    }

    @Test
    void unDestinatarioBancarioNecesitaDireccionEnIso2() {
        var cuenta = new RecipientAccount.Ach("021000021", "1234567890", BankAccountKind.CHECKING,
                "Bank", null, "ein", "12-3");
        var titular = RecipientHolder.company("Acme", null, null);

        assertThrows(DomainException.class,
                () -> new Recipient("r-1", TENANT, titular, cuenta, null));
        // ISO-3 es lo que usa el KYB de la empresa, no el destinatario.
        assertThrows(DomainException.class, () -> new Recipient("r-1", TENANT, titular, cuenta,
                new PostalAddress("1 Main St", "NY", "NY", "10001", "USA")));
        assertDoesNotThrow(() -> new Recipient("r-1", TENANT, titular, cuenta, direccion()));
    }

    @Test
    void unaWalletNoNecesitaDireccionPostal() {
        assertDoesNotThrow(() -> new Recipient("r-1", TENANT,
                RecipientHolder.person("Ana", "Perez", null, null),
                new RecipientAccount.Wallet(WalletToken.USDC, "polygon", "0xabc", null, null),
                null));
    }

    @Test
    void archivarEnlazaConElReemplazo() {
        Recipient r = new Recipient("r-1", TENANT, RecipientHolder.company("Acme", null, null),
                new RecipientAccount.Ach("021000021", "1234567890", BankAccountKind.CHECKING,
                        "Bank", null, null, null), direccion());
        r.linkKiraRecipient("krec-1");

        r.replaceWith("r-2");

        assertEquals(RecipientStatus.ARCHIVED, r.getStatus());
        assertEquals("r-2", r.getReplacedByRecipientId());
        // Un destinatario archivado no admite nuevos pagos.
        assertThrows(DomainException.class, r::assertUsable);
    }

    @Test
    void sinAltaEnKiraNoSePuedeUsar() {
        Recipient r = new Recipient("r-1", TENANT, RecipientHolder.company("Acme", null, null),
                new RecipientAccount.Ach("021000021", "1234567890", BankAccountKind.CHECKING,
                        "Bank", null, null, null), direccion());

        assertThrows(DomainException.class, r::assertUsable);
    }
}
