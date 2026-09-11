package com.example.autransactional.domain.treasury;

/** Ciclo de vida local de la cotizacion. El TTL de 15 minutos lo fija Kira. */
public enum QuotationStatus {
    ACTIVE,
    EXPIRED,
    EXECUTED
}
