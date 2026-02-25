-- resources/db/migration/V1__create_vpa_registry.sql
-- Flyway runs this automatically on startup if it hasn't run before

CREATE TABLE vpa_registry (
    vpa_id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    vpa_address         VARCHAR(100)    NOT NULL,
    user_id             UUID            NOT NULL,
    account_number      VARCHAR(512)    NOT NULL,   -- Stored AES-256 encrypted
    ifsc_code           VARCHAR(11)     NOT NULL,
    account_holder_name VARCHAR(200)    NOT NULL,
    psp_handle          VARCHAR(50)     NOT NULL,
    is_active           BOOLEAN         NOT NULL    DEFAULT TRUE,
    created_at          TIMESTAMP       NOT NULL    DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL    DEFAULT NOW(),

    CONSTRAINT pk_vpa_registry PRIMARY KEY (vpa_id),
    CONSTRAINT uq_vpa_address  UNIQUE (vpa_address)
);

-- Index on user_id for fast lookup of all VPAs by user (FR-5)
CREATE INDEX idx_vpa_user_id ON vpa_registry (user_id);

-- Index on is_active for fast filtering of active VPAs
CREATE INDEX idx_vpa_is_active ON vpa_registry (is_active);

-- Partial index: only index active VPAs on address — faster resolution queries
-- This is an advanced DB optimization: index only the rows you actually query
CREATE UNIQUE INDEX idx_vpa_active_address
    ON vpa_registry (vpa_address)
    WHERE is_active = TRUE;

-- Auto-update updated_at on every row change
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER trigger_vpa_updated_at
    BEFORE UPDATE ON vpa_registry
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
