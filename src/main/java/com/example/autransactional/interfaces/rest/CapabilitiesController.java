package com.example.autransactional.interfaces.rest;

import com.example.autransactional.application.treasury.PayoutApprovalPolicy;
import com.example.autransactional.infrastructure.kira.KiraCredentialManager;
import com.example.autransactional.infrastructure.kira.KiraProperties;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Lo que este entorno permite (G-18).
 *
 * Sin esta ruta el portal tenia que decidir por su propia configuracion de compilacion si mostrar
 * "Simular deposito" o si el proveedor estaba configurado, y una compilacion equivocada ofrecia
 * botones que siempre fallaban. Aqui no hay secretos: solo si hay credenciales, no cuales.
 */
@Tag(name = "0. Catalogos", description = "Capacidades del entorno tal como las ve el portal.")
@RestController
@RequestMapping("/api/capabilities")
public class CapabilitiesController {

    private final KiraProperties properties;
    private final KiraCredentialManager credentials;
    private final PayoutApprovalPolicy approvalPolicy;
    private final String supportEmail;

    public CapabilitiesController(KiraProperties properties, KiraCredentialManager credentials,
                                  PayoutApprovalPolicy approvalPolicy,
                                  @Value("${bff.support.email:}") String supportEmail) {
        this.properties = properties;
        this.credentials = credentials;
        this.approvalPolicy = approvalPolicy;
        this.supportEmail = supportEmail == null || supportEmail.isBlank() ? null : supportEmail;
    }

    @GetMapping
    @Operation(summary = "Capacidades del entorno",
            description = "Sandbox, proveedor configurado, banco y umbral de doble firma de la empresa de la sesion.")
    public CapabilitiesView capabilities(@AuthenticationPrincipal AuthenticatedOperator operator) {
        // El umbral es por empresa (bff.payouts.approval.tenant-thresholds) y el de la plataforma no aplica.
        BigDecimal threshold = operator.role().isPlatform() ? null : approvalPolicy.thresholdFor(operator.tenantId());
        return new CapabilitiesView(
                properties.sandbox(),
                credentials.isConfigured(),
                properties.bank(),
                properties.apiVersion(),
                threshold,
                supportEmail);
    }

    /**
     * @param sandbox            entorno de pruebas del proveedor: existe "simular deposito"
     * @param providerConfigured hay credenciales de Kira; si no, todo lo que llame al proveedor responde 503
     * @param bank               banco de las cuentas virtuales de este entorno
     * @param providerApiVersion version de la API del proveedor que entiende el BFF
     * @param dualApprovalThreshold  desde este importe un pago necesita dos firmas; nulo si no hay umbral
     * @param supportEmail       correo de soporte/operaciones para escalar una incidencia (bff.support.email)
     */
    public record CapabilitiesView(boolean sandbox, boolean providerConfigured, String bank,
                                   String providerApiVersion, BigDecimal dualApprovalThreshold,
                                   String supportEmail) {
    }
}
