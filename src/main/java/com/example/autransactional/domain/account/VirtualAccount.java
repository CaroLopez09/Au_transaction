package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Agregado VirtualAccount: la cuenta bancaria virtual de la empresa cliente en Kira.
 *
 * El saldo es una proyeccion local de GET /v1/virtual-accounts/{id}/balance y de los
 * depositos recibidos por webhook. La autoridad es siempre Kira: aqui solo se refleja.
 */
@Getter
public class VirtualAccount {

    /** Pasado este tiempo sin activarse, deja de ser una espera normal. */
    public static final Duration ACTIVATION_GRACE = Duration.ofMinutes(5);

    private final String id;
    private final TenantId tenantId;
    private final String currency;
    private final VirtualAccountMode mode;
    private final Instant createdAt;

    private String kiraAccountId;
    private String bankName;
    private String accountNumber;
    private String routingNumber;
    private String bank;
    private String description;
    private VirtualAccountStatus status;
    private BigDecimal balanceAvailable;
    private boolean activatedEventSeen;
    private Instant balanceRefreshedAt;
    private String openingIdempotencyKey;
    private Instant updatedAt;

    public VirtualAccount(String id, TenantId tenantId, String currency, VirtualAccountMode mode,
                          String bank, String description) {
        if (currency == null || currency.isBlank()) {
            throw new DomainException("La cuenta virtual necesita una moneda.");
        }
        if (bank == null || bank.isBlank()) {
            // Sin banco, Kira responde 400: y el valor valido depende del entorno.
            throw new DomainException("La cuenta virtual necesita banco.");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.currency = currency;
        this.mode = mode == null ? VirtualAccountMode.FIAT : mode;
        this.bank = bank;
        this.description = description;
        this.status = VirtualAccountStatus.PENDING;
        this.balanceAvailable = BigDecimal.ZERO;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static VirtualAccount rehydrate(String id, TenantId tenantId, String kiraAccountId,
                                           String bankName, String accountNumber, String routingNumber,
                                           String currency, VirtualAccountMode mode, String bank,
                                           String description, VirtualAccountStatus status,
                                           BigDecimal balanceAvailable, boolean activatedEventSeen,
                                           Instant balanceRefreshedAt, String openingIdempotencyKey,
                                           Instant createdAt, Instant updatedAt) {
        VirtualAccount a = new VirtualAccount(id, tenantId, currency, mode, bank, description);
        a.kiraAccountId = kiraAccountId;
        a.bankName = bankName;
        a.accountNumber = accountNumber;
        a.routingNumber = routingNumber;
        a.status = status == null ? VirtualAccountStatus.PENDING : status;
        a.balanceAvailable = balanceAvailable == null ? BigDecimal.ZERO : balanceAvailable;
        a.activatedEventSeen = activatedEventSeen;
        a.balanceRefreshedAt = balanceRefreshedAt;
        a.openingIdempotencyKey = openingIdempotencyKey;
        a.updatedAt = updatedAt;
        return a;
    }

    /**
     * Reserva la clave de idempotencia de la apertura, antes de la primera llamada.
     * Un timeout seguido de reintento no debe dejar dos cuentas abiertas.
     */
    public IdempotencyKey reserveOpeningKey() {
        if (openingIdempotencyKey == null) {
            openingIdempotencyKey = IdempotencyKey.newKey().value();
            touch();
        }
        return IdempotencyKey.of(openingIdempotencyKey);
    }

    public void linkKiraAccount(String kiraAccountId) {
        this.kiraAccountId = kiraAccountId;
        touch();
    }

    /**
     * Completa los datos bancarios. Solo sobrescribe lo que llega con valor: un evento que
     * no trae el numero de cuenta no puede borrar el que ya conocemos, porque ese numero es
     * justamente la senal de que la cuenta puede mover fondos.
     */
    public void describeBank(String bankName, String accountNumber, String routingNumber) {
        if (bankName != null && !bankName.isBlank()) {
            this.bankName = bankName;
        }
        if (accountNumber != null && !accountNumber.isBlank()) {
            this.accountNumber = accountNumber;
        }
        if (routingNumber != null && !routingNumber.isBlank()) {
            this.routingNumber = routingNumber;
        }
        touch();
    }

    public void applyRemoteStatus(VirtualAccountStatus incoming) {
        if (incoming != null) {
            this.status = incoming;
            touch();
        }
    }

    /** virtual_account.activated es la unica senal inequivoca de fondos-listos. */
    public void markActivatedEventSeen() {
        this.activatedEventSeen = true;
        this.status = VirtualAccountStatus.ACTIVE;
        touch();
    }

    /**
     * El saldo es una proyeccion: la autoridad es Kira. En el sandbox ademas es un valor
     * fijo del proveedor que no se mueve con la actividad.
     */
    public void refreshBalance(BigDecimal available, Instant at) {
        if (available != null) {
            this.balanceAvailable = available;
            this.balanceRefreshedAt = at;
            touch();
        }
    }

    /**
     * La activacion lleva demasiado tiempo.
     *
     * En el sandbox puede quedarse colgada indefinidamente sin que llegue nunca el evento
     * virtual_account.activated, asi que el portal necesita poder decir "activacion
     * demorada, contacta con Kira" en vez de girar un spinner para siempre.
     */
    public boolean isActivationDelayed(Instant now) {
        return !isFundsReady() && now.isAfter(createdAt.plus(ACTIVATION_GRACE));
    }

    public boolean isOpenInKira() {
        return kiraAccountId != null;
    }

    /**
     * Marca el saldo como desactualizado.
     *
     * Un deposito acreditado NO se suma al saldo local: la autoridad es Kira y en el
     * sandbox el saldo es ademas un valor fijo del proveedor. Inventar aqui una suma seria
     * mostrar un numero que el banco no reconoce; lo unico honesto es decir que hay que
     * volver a preguntar.
     */
    public void markBalanceStale() {
        this.balanceRefreshedAt = null;
        touch();
    }

    public boolean isBalanceStale() {
        return balanceRefreshedAt == null;
    }

    public Money availableBalance() {
        return Money.of(balanceAvailable, currency);
    }

    public boolean isFundsReady() {
        return VirtualAccountReadiness.isFundsReady(
                status == null ? null : status.name(), accountNumber, activatedEventSeen);
    }

    /** Ningun pago se prepara sobre una cuenta que todavia no puede mover fondos. */
    public void assertFundsReady() {
        if (!isFundsReady()) {
            throw new DomainException("La cuenta virtual todavia no esta habilitada para mover fondos.");
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
