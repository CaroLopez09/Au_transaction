package com.example.autransactional.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Referencia auditable del intento; las imagenes viven exclusivamente en el proveedor biometrico. */
@Entity
@Table(name = "identity_verification_attempts", indexes = {
        @Index(name = "idx_identity_attempt_user", columnList = "user_id"),
        @Index(name = "idx_identity_attempt_expires", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class IdentityVerificationAttemptEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "provider_verification_id", length = 150)
    private String providerVerificationId;

    @Column(name = "challenge_id", length = 100)
    private String challengeId;

    @Column(name = "liveness_session_id", length = 150)
    private String livenessSessionId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}