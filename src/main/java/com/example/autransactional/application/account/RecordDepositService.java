package com.example.autransactional.application.account;

import com.example.autransactional.domain.account.Deposit;
import com.example.autransactional.domain.account.DepositRepository;
import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Tope de Kira por pagina en el listado de depositos de una cuenta. */
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;

    private final DepositRepository deposits;
    private final VirtualAccountRepository accounts;
    private final KiraApiClient kira;

    public RecordDepositService(DepositRepository deposits, VirtualAccountRepository accounts, KiraApiClient kira) {
        this.deposits = deposits;
        this.accounts = accounts;
        this.kira = kira;
    }

    /**
     * Trae de Kira los depositos de una cuenta y los asienta con la misma proyeccion que los
     * webhooks, asi que converge en las mismas filas. Es la red de seguridad de un evento
     * perdido (entrega unica, sin reintentos). En el sandbox Kira no devuelve nada aqui.
     */
    @Transactional
    public List<DepositView> syncFromKira(AuthenticatedOperator operator, String accountId) {
        VirtualAccount account = accounts.findByIdAndTenant(accountId, operator.tenantId())
                .orElseThrow(() -> new DomainException("Cuenta virtual no encontrada."));
        if (account.getKiraAccountId() == null) {
            throw new DomainException("La cuenta virtual no esta abierta en Kira.");
        }
        int asentados = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("limit", PAGE_SIZE);
            query.put("offset", page * PAGE_SIZE);
            JsonNode response = kira.listAccountDeposits(account.getKiraAccountId(), query);
            JsonNode list = response.isArray() ? response : response.path("data");
            int size = 0;
            for (JsonNode resource : list) {
                size++;
                KiraDepositEvent event = KiraDepositEvent.fromResource(resource, account.getKiraAccountId());
                // Aislamiento: un deposito de otra cuenta no se asienta aqui aunque venga en la lista.
                if (!account.getKiraAccountId().equals(event.kiraAccountId())) {
                    continue;
                }
                apply(event);
                asentados++;
            }
            // Sin envoltorio de paginacion: hay mas si la pagina vino llena.
            if (size < PAGE_SIZE) {
                break;
            }
        }
        log.info("Sincronizados {} depositos de la cuenta {} desde Kira.", asentados, account.getId());
        return listByAccount(operator, accountId, 200);
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
