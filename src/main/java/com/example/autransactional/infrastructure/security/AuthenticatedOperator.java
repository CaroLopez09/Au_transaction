package com.example.autransactional.infrastructure.security;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;

/** Principal autenticado. Se expone como principal de Spring Security. */
public record AuthenticatedOperator(String userId, String email, TenantId tenantId, Role role) {
}
