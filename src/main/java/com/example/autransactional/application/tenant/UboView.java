package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.PostalAddress;
import com.example.autransactional.domain.tenant.Ubo;
import com.example.autransactional.domain.tenant.UboRoster;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record UboView(
        String id,
        String personReferenceId,
        String fullName,
        String firstName,
        String lastName,
        String email,
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
        LocalDate birthDate,
        String nationality,
        String occupation,
        String gender,
        String phoneNumber,
        String documentCountry,
        Address address,
        boolean knownToKira,
        String livenessStatus,
        String livenessLink,
        Instant livenessExpiresAt) {

    public static UboView from(Ubo u) {
        return new UboView(
                u.getId(),
                u.getPersonReferenceId(),
                u.fullName(),
                u.getFirstName(),
                u.getLastName(),
                u.getEmail(),
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
                u.getBirthDate(),
                u.getNationality(),
                u.getOccupation(),
                u.getGender(),
                u.getPhoneNumber(),
                u.getDocumentCountry(),
                Address.from(u.getResidentialAddress()),
                u.isKnownToKira(),
                u.getLivenessStatus().name(),
                u.getLivenessLink(),
                u.getLivenessExpiresAt());
    }

    /** Direccion de residencia tal como la ve el portal (pais ISO-3). */
    public record Address(String streetName, String city, String state, String postalCode, String country) {

        static Address from(PostalAddress address) {
            return address == null ? null : new Address(address.streetName(), address.city(), address.state(),
                    address.postalCode(), address.country());
        }
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
