package com.example.autransactional.application.account;

import com.example.autransactional.domain.account.VirtualAccount;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cuenta virtual para el portal.
 *
 * fundsReady es la pregunta que de verdad importa y NO es lo mismo que el estado: la API
 * colapsa activating y active en 'approved', asi que una cuenta 'activa' puede seguir sin
 * poder mover fondos. Manda el numero de cuenta real o el evento de activacion.
 */
public record VirtualAccountView(
        String id,
        String kiraAccountId,
        String status,
        String mode,
        String bank,
        String bankName,
        String description,
        String accountNumber,
        String routingNumber,
        String currency,
        BigDecimal availableBalance,
        Instant balanceRefreshedAt,
        boolean balanceStale,
        boolean fundsReady,
        boolean activationDelayed,
        Instant createdAt) {

    public static VirtualAccountView from(VirtualAccount a) {
        return new VirtualAccountView(
                a.getId(),
                a.getKiraAccountId(),
                a.getStatus().name(),
                a.getMode().name(),
                a.getBank(),
                a.getBankName(),
                a.getDescription(),
                // Las instrucciones de deposito son numero + routing: el portal las copia tal cual.
                a.getAccountNumber(),
                a.getRoutingNumber(),
                a.getCurrency(),
                a.getBalanceAvailable(),
                a.getBalanceRefreshedAt(),
                // Un deposito acreditado invalida el saldo cacheado; hay que volver a pedirlo.
                a.isBalanceStale(),
                a.isFundsReady(),
                a.isActivationDelayed(Instant.now()),
                a.getCreatedAt());
    }
}
