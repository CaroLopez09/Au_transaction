package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.tenant.Ubo;
import com.example.autransactional.domain.tenant.UboRoster;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UboView(
        String id,
        String personReferenceId,
        String fullName,
        String documentType,
        String documentNumber,
        boolean hasOwnership,
        BigDecimal ownershipPercentage,
        boolean beneficialOwner,
        boolean hasControl,
        boolean signer,
        boolean politicallyExposed,
        String countryOfBirth,
        String roleInCompany,
        String livenessStatus,
        String livenessLink,
        Instant livenessExpiresAt) {

    public static UboView from(Ubo u) {
        return new UboView(
                u.getId(),
                u.getPersonReferenceId(),
                u.fullName(),
                u.getDocumentType(),
                u.getDocumentNumber(),
                u.isHasOwnership(),
                u.getOwnershipPercentage(),
                u.isBeneficialOwner(),
                u.isHasControl(),
                u.isSigner(),
                u.isPoliticallyExposed(),
                u.getCountryOfBirth(),
                u.getRoleInCompany(),
                u.getLivenessStatus().name(),
                u.getLivenessLink(),
                u.getLivenessExpiresAt());
    }

    /** Vista del grupo: lo que Kira valida sobre el conjunto, no sobre cada persona. */
    public record Roster(List<UboView> members, BigDecimal totalOwnership,
                         boolean hasBeneficialOwner, boolean livenessComplete) {

        public static Roster from(UboRoster roster) {
            return new Roster(
                    roster.members().stream().map(UboView::from).toList(),
                    roster.totalOwnership(),
                    roster.hasBeneficialOwner(),
                    roster.livenessComplete());
        }
    }
}
