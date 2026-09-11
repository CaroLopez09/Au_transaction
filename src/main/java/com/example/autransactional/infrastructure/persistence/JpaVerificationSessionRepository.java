package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.compliance.identity.DocumentData;
import com.example.autransactional.domain.compliance.identity.NumberChallenge;
import com.example.autransactional.domain.compliance.identity.VerificationSession;
import com.example.autransactional.domain.compliance.identity.VerificationSessionRepository;
import com.example.autransactional.domain.shared.TenantId;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaVerificationSessionRepository implements VerificationSessionRepository {

    private final VerificationSessionJpaRepository jpa;

    public JpaVerificationSessionRepository(VerificationSessionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public VerificationSession save(VerificationSession session) {
        VerificationSessionEntity entity = jpa.findById(session.getId()).orElseGet(VerificationSessionEntity::new);
        jpa.save(toEntity(session, entity));
        return session;
    }

    @Override
    public Optional<VerificationSession> findById(String id) {
        return jpa.findById(id).map(JpaVerificationSessionRepository::toDomain);
    }

    @Override
    public Optional<VerificationSession> findByIdAndTenant(String id, TenantId tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId.value()).map(JpaVerificationSessionRepository::toDomain);
    }

    @Override
    public Optional<VerificationSession> findByChallengeId(String challengeId) {
        return jpa.findByChallengeId(challengeId).map(JpaVerificationSessionRepository::toDomain);
    }

    @Override
    public Optional<VerificationSession> findByLivenessSessionId(String livenessSessionId) {
        return jpa.findByLivenessSessionId(livenessSessionId).map(JpaVerificationSessionRepository::toDomain);
    }

    private static VerificationSessionEntity toEntity(VerificationSession s, VerificationSessionEntity e) {
        e.setId(s.getId());
        e.setTenantId(s.getTenantId().value());
        e.setCorrelationId(s.getCorrelationId());
        e.setCountryCode(s.getCountryCode());
        e.setDocumentType(s.getDocumentType());
        e.setKiraUserId(s.getKiraUserId());

        NumberChallenge challenge = s.getChallenge();
        if (challenge != null) {
            e.setChallengeId(challenge.getId());
            e.setChallengeNumber(challenge.getNumber());
            e.setChallengeExpiresAt(challenge.getExpiresAt());
            e.setChallengeUsed(challenge.isUsed());
            e.setChallengeAttempts(challenge.getAttempts());
        }
        e.setChallengePassed(s.isChallengePassed());
        e.setChallengeConfidence(s.getChallengeConfidence());

        e.setLivenessSessionId(s.getLivenessSessionId());
        e.setLivenessPassed(s.isLivenessPassed());
        e.setLivenessScore(s.getLivenessScore());
        e.setMatchScore(s.getMatchScore());

        DocumentData doc = s.getDocumentData();
        if (doc != null) {
            e.setDocumentNumber(doc.documentNumber());
            e.setDocumentFirstName(doc.firstName());
            e.setDocumentLastName(doc.lastName());
            e.setDocumentBirthDate(doc.birthDate());
            e.setDocumentExpirationDate(doc.expirationDate());
            e.setDocumentIssuingCountry(doc.issuingCountry());
            e.setDocumentNationality(doc.nationality());
            e.setDocumentGender(doc.gender());
        }

        e.setVerdict(s.getVerdict());
        e.setFailureCode(s.getFailureCode());
        e.setCreatedAt(s.getCreatedAt());
        e.setExpiresAt(s.getExpiresAt());
        e.setCompletedAt(s.getCompletedAt());
        return e;
    }

    private static VerificationSession toDomain(VerificationSessionEntity e) {
        NumberChallenge challenge = e.getChallengeId() == null ? null
                : NumberChallenge.rehydrate(e.getChallengeId(), e.getChallengeNumber(),
                e.getChallengeExpiresAt(), e.isChallengeUsed(), e.getChallengeAttempts());

        DocumentData doc = e.getDocumentNumber() == null ? null
                : new DocumentData(e.getDocumentNumber(), e.getDocumentFirstName(), e.getDocumentLastName(),
                e.getDocumentBirthDate(), e.getDocumentExpirationDate(), e.getDocumentIssuingCountry(),
                e.getDocumentNationality(), e.getDocumentGender());

        return VerificationSession.rehydrate(e.getId(), TenantId.of(e.getTenantId()), e.getCorrelationId(),
                e.getCreatedAt(), e.getExpiresAt(), e.getCountryCode(), e.getDocumentType(), e.getKiraUserId(),
                challenge, e.isChallengePassed(), e.getChallengeConfidence(), e.getLivenessSessionId(),
                e.isLivenessPassed(), e.getLivenessScore(), e.getMatchScore(), doc,
                e.getVerdict(), e.getFailureCode(), e.getCompletedAt());
    }
}
