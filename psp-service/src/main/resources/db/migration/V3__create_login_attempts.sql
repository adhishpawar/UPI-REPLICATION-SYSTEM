-- V3__create_login_attempts.sql
-- Append-only audit log. NEVER delete or update rows.

CREATE TABLE login_attempts (
    attempt_id    UUID         NOT NULL DEFAULT gen_random_uuid(),
    mobile_number VARCHAR(15)  NOT NULL,
    device_id     VARCHAR(255) NOT NULL,
    ip_address    VARCHAR(45)  NULL,      -- IPv4 or IPv6
    success       BOOLEAN      NOT NULL,
    failure_reason VARCHAR(100) NULL,     -- INVALID_MPIN, ACCOUNT_LOCKED, etc.
    attempted_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_login_attempts PRIMARY KEY (attempt_id)
);

-- For security reports: all attempts for a mobile number
CREATE INDEX idx_attempts_mobile ON login_attempts (mobile_number, attempted_at DESC);
-- For fraud detection: all attempts from an IP
CREATE INDEX idx_attempts_ip ON login_attempts (ip_address, attempted_at DESC);
