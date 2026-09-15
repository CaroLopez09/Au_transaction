package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Limites internos de aprobacion (arquitectura §5 y §8), fijos en configuracion por decision del
 * 15-sep. A partir del umbral un pago necesita dos aprobadores distintos, ninguno el que lo creo.
 *
 * @param dualApprovalThreshold umbral por defecto, en la moneda del pago; null lo desactiva
 * @param tenantThresholds      umbral propio de una empresa (id del BFF), que manda sobre el general
 */
@ConfigurationProperties(prefix = "bff.payouts.approval")
public record PayoutApprovalPolicy(BigDecimal dualApprovalThreshold, Map<String, BigDecimal> tenantThresholds) {

    public PayoutApprovalPolicy {
        tenantThresholds = tenantThresholds == null ? Map.of() : Map.copyOf(tenantThresholds);
    }

    public BigDecimal thresholdFor(TenantId tenantId) {
        return tenantThresholds.getOrDefault(tenantId.value(), dualApprovalThreshold);
    }

    public int requiredApprovals(TenantId tenantId, Money amount) {
        BigDecimal threshold = thresholdFor(tenantId);
        return threshold != null && amount.amount().compareTo(threshold) >= 0 ? 2 : 1;
    }
}
