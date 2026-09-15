package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Borrador del formulario de vinculacion.
 *
 * Kira no conoce borradores: un PUT escribe lo que se envia y la verificacion arranca sola
 * cuando el expediente esta completo. Para poder dejar el formulario a medias y volver otro
 * dia, el portal guarda aqui lo que lleva rellenado. Este servicio NUNCA llama a Kira: enviar
 * sigue siendo POST/PUT /api/onboarding.
 *
 * Los documentos no caben en un borrador por la misma regla que en el resto del BFF: los
 * archivos los custodia Kira y no se guardan aqui. Un data URI dentro del borrador se rechaza.
 */
@Service
public class OnboardingDraftService {

    private static final TypeReference<Map<String, Object>> DRAFT = new TypeReference<>() {
    };

    private final TenantRepository tenants;
    private final AuditTrail audit;
    private final ObjectMapper objectMapper;

    public OnboardingDraftService(TenantRepository tenants, AuditTrail audit, ObjectMapper objectMapper) {
        this.tenants = tenants;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OnboardingDraftView get(AuthenticatedOperator operator) {
        Tenant tenant = load(operator.tenantId());
        return new OnboardingDraftView(read(tenant.getOnboardingDraft()), tenant.getOnboardingDraftUpdatedAt());
    }

    @Transactional
    public OnboardingDraftView save(AuthenticatedOperator operator, OnboardingCommands.SaveDraft command) {
        if (!operator.role().canManageCompliance()) {
            throw new DomainException("Tu rol no puede gestionar el onboarding de la empresa.");
        }
        Tenant tenant = load(operator.tenantId());
        tenant.assertActive();
        Map<String, Object> draft = command.draft() == null ? Map.of() : command.draft();
        assertWithoutFiles(draft);
        Instant now = Instant.now();
        tenant.saveOnboardingDraft(draft.isEmpty() ? null : objectMapper.writeValueAsString(draft), now);
        tenants.save(tenant);
        // Solo los nombres de las secciones: la bitacora no guarda datos de la empresa.
        audit.record(operator, "tenant.onboarding_draft_saved", "tenant", tenant.getId().value(), null, "OK",
                "secciones=" + draft.keySet());
        return new OnboardingDraftView(draft, now);
    }

    private static void assertWithoutFiles(Object value) {
        if (value instanceof String text && text.regionMatches(true, 0, "data:", 0, 5)) {
            throw new DomainException("El borrador no admite archivos: los documentos se suben al proveedor en su paso.");
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(OnboardingDraftService::assertWithoutFiles);
        } else if (value instanceof Collection<?> list) {
            list.forEach(OnboardingDraftService::assertWithoutFiles);
        }
    }

    /** Un borrador ilegible no debe bloquear el formulario: se degrada a vacio. */
    private Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, DRAFT));
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private Tenant load(TenantId tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> new DomainException("La organizacion no existe."));
    }
}
