-- V2__create_auth_tokens.sql

CREATE TABLE IF NOT EXISTS auth_tokens (
    token_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL,
    token_hash    VARCHAR(512) NOT NULL,  -- SHA-256 hash of the JWT string
    token_type    VARCHAR(10)  NOT NULL DEFAULT 'ACCESS',  -- ACCESS or REFRESH
    device_id     VARCHAR(255) NOT NULL,
    is_revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    issued_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP    NOT NULL,
    revoked_at    TIMESTAMP    NULL,

    CONSTRAINT pk_auth_tokens PRIMARY KEY (token_id),
    CONSTRAINT fk_tokens_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Login/logout checks if a token is valid: hash + not revoked
CREATE INDEX IF NOT EXISTS idx_token_hash ON auth_tokens (token_hash) WHERE is_revoked = FALSE;
-- Cleanup job: find expired tokens to purge
CREATE INDEX IF NOT EXISTS idx_token_expiry ON auth_tokens (expires_at);