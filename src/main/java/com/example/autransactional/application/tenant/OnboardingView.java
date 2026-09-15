package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.Tenant;

import java.util.List;

/**
 * Proyeccion del estado del KYB para el portal.
 *
 * pendingFields es lo que la pantalla debe renderizar: no hay formulario estatico, se
 * dibuja desde lo que Kira sigue pidiendo para el producto objetivo.
 */
public record OnboardingView(
        String tenantId,
        String name,
        String kiraUserId,
        String status,
        /** Motivos de user.verification.failed. Solo llegan por webhook: ningun GET los devuelve. */
        String rejectionReason,
        boolean verificationTriggered,
        List<String> pendingFields,
        List<EligibleProduct> eligibleProducts,
        boolean readyForVirtualAccounts,
        boolean enhancedDueDiligenceRequired) {

    public static OnboardingView from(Tenant tenant) {
        String product = EligibleProduct.USA_VIRTUAL_ACCOUNTS;
        return new OnboardingView(
                tenant.getId().value(),
                tenant.getName(),
                tenant.getKiraUserId(),
                tenant.getStatus().name(),
                tenant.getRejectionReason(),
                tenant.isVerificationTriggered(),
                tenant.getMissingFields().forProduct(product),
                tenant.getEligibleProducts(),
                tenant.isReadyFor(product),
                tenant.product(product)
                        .map(EligibleProduct::requiresEnhancedDueDiligence)
                        .orElse(false));
    }
}
