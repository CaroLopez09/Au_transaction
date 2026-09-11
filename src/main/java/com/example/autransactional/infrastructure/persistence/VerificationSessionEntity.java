package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.compliance.identity.IdentityErrorCode;
import com.example.autransactional.domain.compliance.identity.VerificationVerdict;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Sesion de verificacion biometrica.
 *
 * Nota de retencion: aqui NO se guardan la selfie, el fotograma del liveness ni las imagenes
 * del documento. Solo el veredicto, los puntajes y los datos minimos del documento. Es la
 * recomendacion S-3: cada log o herramienta que toque este registro es superficie de
 * exposicion de biometria y datos personales.
 */
@Entity
@Table(name = "verification_sessions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_verif_challenge", columnNames = "challenge_id"),
                @UniqueConstraint(name = "uk_verif_liveness", columnNames = "liveness_session_id")
        },
        indexes = @Index(name = "ix_verif_tenant", columnList = "tenant_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class VerificationSessionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "country_code", length = 3)
    private String countryCode;

    @Column(name = "document_type", length = 20)
    private String documentType;

    @Column(name = "kira_user_id", length = 64)
    private String kiraUserId;

    @Column(name = "challenge_id", length = 64)
    private String challengeId;

    @Column(name = "challenge_number", length = 8)
    private String challengeNumber;

    @Column(name = "challenge_expires_at")
    private Instant challengeExpiresAt;

    @Column(name = "challenge_used")
    private boolean challengeUsed;

    @Column(name = "challenge_attempts")
    private int challengeAttempts;

    @Column(name = "challenge_passed")
    private boolean challengePassed;

    @Column(name = "challenge_confidence")
    private double challengeConfidence;

    @Column(name = "liveness_session_id", length = 64)
    private String livenessSessionId;

    @Column(name = "liveness_passed")
    private boolean livenessPassed;

    @Column(name = "liveness_score")
    private double livenessScore;

    @Column(name = "match_score")
    private double matchScore;

    @Column(name = "doc_number", length = 60)
    private String documentNumber;

    @Column(name = "doc_first_name", length = 120)
    private String documentFirstName;

    @Column(name = "doc_last_name", length = 120)
    private String documentLastName;

    @Column(name = "doc_birth_date", length = 20)
    private String documentBirthDate;

    @Column(name = "doc_expiration_date", length = 20)
    private String documentExpirationDate;

    @Column(name = "doc_issuing_country", length = 3)
    private String documentIssuingCountry;

    @Column(name = "doc_nationality", length = 3)
    private String documentNationality;

    @Column(name = "doc_gender", length = 8)
    private String documentGender;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private VerificationVerdict verdict;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "failure_code", length = 40)
    private IdentityErrorCode failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
