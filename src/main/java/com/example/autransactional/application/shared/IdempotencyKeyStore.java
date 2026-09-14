package com.example.autransactional.application.shared;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consolida en base de datos la clave de idempotencia ANTES de llamar a Kira.
 *
 * El caso de uso que abre la empresa o la cuenta corre dentro de una transaccion y relanza la
 * excepcion si Kira falla: ese rollback tambien deshacia el guardado de la clave, justo lo que
 * no debe perderse. Si Kira llego a crear el recurso y la respuesta se perdio, el reintento
 * tiene que viajar con LA MISMA clave o se crea un duplicado.
 *
 * Por eso cada metodo abre su propia transaccion (REQUIRES_NEW) y confirma antes de volver:
 * pase lo que pase despues, la clave ya esta en MySQL. Vive en una clase aparte a proposito,
 * porque una llamada interna al propio servicio no pasa por el proxy de Spring y la propagacion
 * no se aplicaria.
 */
@Service
public class IdempotencyKeyStore {

    private final TenantRepository tenants;
    private final VirtualAccountRepository accounts;

    public IdempotencyKeyStore(TenantRepository tenants, VirtualAccountRepository accounts) {
        this.tenants = tenants;
        this.accounts = accounts;
    }

    /** Guarda la empresa con su clave de alta reservada, en una transaccion propia. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistNow(Tenant tenant) {
        tenants.save(tenant);
    }

    /** Guarda la cuenta virtual con su clave de apertura reservada, en una transaccion propia. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistNow(VirtualAccount account) {
        accounts.save(account);
    }
}
