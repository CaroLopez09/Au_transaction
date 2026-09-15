package com.example.autransactional.application.tenant;

import java.time.Instant;
import java.util.Map;

/** Borrador del formulario de vinculacion tal como lo dejo el portal. `updatedAt` es nulo si nunca se guardo. */
public record OnboardingDraftView(Map<String, Object> draft, Instant updatedAt) {
}
