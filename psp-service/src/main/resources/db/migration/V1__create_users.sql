CREATE TABLE users (
    user_id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    mobile_number        VARCHAR(15)   NOT NULL,  -- E.164: +91XXXXXXXXXX
    device_id            VARCHAR(255)  NOT NULL,  -- Hardware device identifier
    device_fingerprint   VARCHAR(512)  NOT NULL,  -- SHA-256 hash of device attrs
    mpin_hash            VARCHAR(60)   NULL,       -- BCrypt hash. NULL until setup.
    status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING_MPIN',
    failed_login_count   INT           NOT NULL DEFAULT 0,
    last_failed_login_at TIMESTAMP     NULL,
    is_active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_users PRIMARY KEY (user_id),
    -- A user can have the same mobile on a new device (upgrade scenario)
    -- but same mobile+device combo must be unique
    CONSTRAINT uq_mobile_device UNIQUE (mobile_number, device_id),
    CONSTRAINT chk_status CHECK (status IN ('PENDING_MPIN','ACTIVE','SUSPENDED','LOCKED'))
);

-- Fast lookup by mobile number (used in login)
CREATE INDEX idx_users_mobile ON users (mobile_number);
-- Fast lookup for active users only
CREATE INDEX idx_users_active ON users (mobile_number) WHERE is_active = TRUE;
