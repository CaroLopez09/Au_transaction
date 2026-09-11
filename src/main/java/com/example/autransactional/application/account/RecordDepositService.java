package com.example.autransactional.application.account;

import com.example.autransactional.domain.account.Deposit;
import com.example.autransactional.domain.account.DepositRepository;
import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Depositos entrantes.
 *
 * El espejo local no es una comodidad: en el sandbox un deposito entrante NO aparece en
 * GET /deposits, asi que el webhook es la unica constancia que va a existir de que ese
 * dinero llego.
 */
@Service
public class RecordDepositService {

    private static final Logger log = LoggerFactory.getLogger(RecordDepositService.class);

    private final DepositRepository deposits;
    private final VirtualAccountRepository accounts;

    public RecordDepositService(DepositRepository deposits, VirtualAccountRepository accounts) {
        this.deposits = deposits;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<DepositView> list(AuthenticatedOperator operator, int limit) {
        return deposits.findByTenant(operator.tenantId(), Math.min(limit, 200)).stream()
                .map(DepositView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DepositView> listByAccount(AuthenticatedOperator operator, String accountId, int limit) {
        // El filtro por tenant es parte de la consulta: un id manipulado no cruza organizaciones.
        VirtualAccount account = accounts.findByIdAndTenant(accountId, operator.tenantId())
                .orElseThrow(() -> new DomainException("Cuenta virtual no encontrada."));
        return deposits.findByVirtualAccount(account.getId(), Math.min(limit, 200)).stream()
                .map(DepositView::from)
                .toList();
    }

    /**
     * Proyecta un evento de deposito.
     *
     * Es idempotente por kira_deposit_id: la familia tiene seis eventos que describen el
     * mismo deposito en distintos momentos, y todos deben converger en una sola fila.
     */
    @Transactional
    public void apply(KiraDepositEvent event) {
        if (!event.isIdentifiable()) {
            log.warn("Evento de deposito sin identificador de deposito o de cuenta: no se proyecta.");
            return;
        }
        Optional<VirtualAccount> found = accounts.findByKiraAccountId(event.kiraAccountId());
        if (found.isEmpty()) {
            log.info("Deposito {} sobre una cuenta virtual sin correspondencia local.",
                    event.kiraDepositId());
            return;
        }
        VirtualAccount account = found.get();

        Deposit deposit = deposits.findByKiraDepositId(event.kiraDepositId())
                .map(existing -> update(existing, event))
                .orElseGet(() -> create(account, event));

        deposits.save(deposit);

        if (deposit.creditsBalance()) {
            // No se suma nada al saldo local: la autoridad es Kira. Solo se marca que
            // el saldo cacheado ya no vale y hay que volver a preguntarlo.
            account.markBalanceStale();
            accounts.save(account);
        }
    }

    private Deposit create(VirtualAccount account, KiraDepositEvent event) {
        BigDecimal gross = event.grossAmount();
        if (gross == null || gross.signum() <= 0) {
            throw new DomainException("El evento de deposito no trae importe utilizable.");
        }
        Deposit deposit = new Deposit(
                UUID.randomUUID().toString(),
                account.getTenantId(),
                account.getId(),
                gross,
                event.feeAmount(),
                event.currency() == null ? account.getCurrency() : event.currency());

        deposit.linkKiraDeposit(event.kiraDepositId());
        deposit.restate(gross, event.feeAmount(), event.netAmount());
        deposit.describeSender(event.senderName(), event.senderAccount(), event.rail());
        deposit.applyRemoteStatus(event.status());
        if (event.microdeposit()) {
            deposit.markAsMicrodeposit();
        }
        return deposit;
    }

    private Deposit update(Deposit deposit, KiraDepositEvent event) {
        deposit.restate(event.grossAmount(), event.feeAmount(), event.netAmount());
        deposit.describeSender(event.senderName(), event.senderAccount(), event.rail());
        deposit.applyRemoteStatus(event.status());
        if (event.microdeposit()) {
            deposit.markAsMicrodeposit();
        }
        return deposit;
    }

    /** Solo para lecturas internas del reconciliador. */
    @Transactional(readOnly = true)
    public List<DepositView> listForTenant(TenantId tenantId, int limit) {
        return deposits.findByTenant(tenantId, limit).stream().map(DepositView::from).toList();
    }
}
