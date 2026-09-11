package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.PostalAddress;
import com.example.autransactional.domain.shared.Rail;

/**
 * Datos de cobro del destinatario. Es el oneOf que Kira discrimina por account_type.
 *
 * Un destinatario = un riel. Modelarlo como jerarquia sellada y no como una bolsa de
 * campos opcionales es lo que impide que exista un destinatario ACH con swift_code, o una
 * wallet con numero de cuenta: combinaciones que la API acepta enviar y rechaza al pagar.
 */
public sealed interface RecipientAccount {

    Rail rail();

    /** Numero de cuenta o direccion de wallet, segun el riel. */
    String destination();

    String docType();

    String docNumber();

    /** Cuenta ACH: bank_address viaja como texto plano. */
    record Ach(String routingNumber, String accountNumber, BankAccountKind kind,
               String bankName, String bankAddressText, String docType, String docNumber)
            implements RecipientAccount {

        public Ach {
            assertRoutingNumber(routingNumber);
            assertAccountNumber(accountNumber);
            if (kind == null) {
                throw new DomainException("La cuenta ACH necesita tipo (checking o savings).");
            }
        }

        @Override
        public Rail rail() {
            return Rail.ACH;
        }

        @Override
        public String destination() {
            return accountNumber;
        }
    }

    /** Cuenta WIRE: bank_address viaja como OBJETO, no como texto. */
    record Wire(String routingNumber, String swiftCode, String accountNumber, BankAccountKind kind,
                String bankName, PostalAddress bankAddress, String docType, String docNumber)
            implements RecipientAccount {

        public Wire {
            assertRoutingNumber(routingNumber);
            assertAccountNumber(accountNumber);
            if (kind == null) {
                throw new DomainException("La cuenta WIRE necesita tipo (checking o savings).");
            }
            if (swiftCode != null && !swiftCode.isBlank()
                    && !swiftCode.matches("[A-Za-z]{4}[A-Za-z]{2}[A-Za-z0-9]{2}([A-Za-z0-9]{3})?")) {
                throw new DomainException("El codigo SWIFT/BIC debe tener 8 u 11 caracteres.");
            }
        }

        @Override
        public Rail rail() {
            return Rail.WIRE;
        }

        @Override
        public String destination() {
            return accountNumber;
        }
    }

    /** Wallet de stablecoin. El par token/red se valida al construirla. */
    record Wallet(WalletToken token, String network, String address,
                  String docType, String docNumber) implements RecipientAccount {

        public Wallet {
            if (token == null) {
                throw new DomainException("El destinatario de wallet necesita token.");
            }
            token.assertSupportedOn(network);
            if (address == null || address.isBlank()) {
                throw new DomainException("El destinatario de wallet necesita direccion.");
            }
            network = network.trim().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public Rail rail() {
            return Rail.WALLET;
        }

        @Override
        public String destination() {
            return address;
        }
    }

    private static void assertRoutingNumber(String routingNumber) {
        if (routingNumber == null || !routingNumber.matches("\\d{9}")) {
            throw new DomainException("El routing number debe tener exactamente 9 digitos.");
        }
    }

    private static void assertAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new DomainException("La cuenta del destinatario necesita numero.");
        }
    }
}
