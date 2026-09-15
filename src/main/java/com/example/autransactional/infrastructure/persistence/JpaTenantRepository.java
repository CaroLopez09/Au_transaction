package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.MissingFields;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JpaTenantRepository implements TenantRepository {

    private static final TypeReference<List<EligibleProduct>> PRODUCTS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, List<String>>> FIELDS = new TypeReference<>() {
    };

    private final TenantJpaRepository jpa;
    private final ObjectMapper objectMapper;

    public JpaTenantRepository(TenantJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    public Tenant save(Tenant tenant) {
        TenantEntity e = jpa.findById(tenant.getId().value()).orElseGet(TenantEntity::new);
        e.setId(tenant.getId().value());
        e.setName(tenant.getName());
        e.setTaxId(tenant.getTaxId());
        e.setJurisdiction(tenant.getJurisdiction());
        e.setKiraUserId(tenant.getKiraUserId());
        e.setStatus(tenant.getStatus());
        e.setEligibleProducts(write(tenant.getEligibleProducts().isEmpty()
                ? null : tenant.getEligibleProducts()));
        e.setMissingFields(write(tenant.getMissingFields().isEmpty()
                ? null : tenant.getMissingFields().byProduct()));
        e.setVerificationTriggered(tenant.isVerificationTriggered());
        e.setOnboardingPayload(tenant.getOnboardingPayload());
        e.setOnboardingDraft(tenant.getOnboardingDraft());
        e.setOnboardingDraftUpdatedAt(tenant.getOnboardingDraftUpdatedAt());
        e.setOnboardingIdempotencyKey(tenant.getOnboardingIdempotencyKey());
        e.setRejectionReason(tenant.getRejectionReason());
        e.setCreatedAt(tenant.getCreatedAt());
        e.setUpdatedAt(tenant.getUpdatedAt());
        jpa.save(e);
        return tenant;
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return jpa.findById(id.value()).map(this::toDomain);
    }

    @Override
    public Optional<Tenant> findByKiraUserId(String kiraUserId) {
        return jpa.findByKiraUserId(kiraUserId).map(this::toDomain);
    }

    @Override
    public List<Tenant> findAll() {
        return jpa.findAll().stream().map(this::toDomain).toList();
    }

    private Tenant toDomain(TenantEntity e) {
        Tenant tenant = Tenant.rehydrate(TenantId.of(e.getId()), e.getName(), e.getTaxId(), e.getJurisdiction(),
                e.getKiraUserId(), e.getStatus(),
                read(e.getEligibleProducts(), PRODUCTS, List.of()),
                new MissingFields(read(e.getMissingFields(), FIELDS, Map.of())),
                e.isVerificationTriggered(), e.getOnboardingPayload(), e.getOnboardingIdempotencyKey(),
                e.getRejectionReason(), e.getCreatedAt(), e.getUpdatedAt());
        tenant.restoreOnboardingDraft(e.getOnboardingDraft(), e.getOnboardingDraftUpdatedAt());
        return tenant;
    }

    private String write(Object value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }

    /** Un JSON ilegible no debe impedir leer la empresa cliente: se degrada a vacio. */
    private <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
