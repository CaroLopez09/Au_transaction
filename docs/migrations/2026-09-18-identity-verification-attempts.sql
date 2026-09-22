CREATE TABLE identity_verification_attempts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    provider_verification_id VARCHAR(150) NULL,
    challenge_id VARCHAR(100) NULL,
    liveness_session_id VARCHAR(150) NULL,
    expires_at TIMESTAMP NOT NULL,
    submitted_at TIMESTAMP NULL,
    decided_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    INDEX idx_identity_attempt_user (user_id),
    INDEX idx_identity_attempt_expires (expires_at),
    CONSTRAINT fk_identity_attempt_user FOREIGN KEY (user_id) REFERENCES users(id)
);