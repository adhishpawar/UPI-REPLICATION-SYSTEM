-- V5 - the self-healing subsystem's own records.
--
-- OWNERSHIP (decision D-001): recovery does NOT write transaction state.
-- It owns its investigation and its decision; the Payment Orchestrator applies
-- that decision through the same state machine that guards every other
-- transition. Two writers of transaction state means no source of truth for
-- whether money moved.

CREATE TABLE recovery_cases (
    case_id         UUID          NOT NULL DEFAULT gen_random_uuid(),
    transaction_id  UUID          NOT NULL,
    detected_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    detected_state  VARCHAR(30)   NOT NULL,   -- state when the anomaly was found
    classification  VARCHAR(40)   NULL,       -- UNKNOWN_OUTCOME|KNOWN_FAILURE|TRANSIENT
    strategy        VARCHAR(40)   NULL,       -- RECONCILE|COMPENSATE|RETRY|ESCALATE
    attempts        INT           NOT NULL DEFAULT 0,
    outcome         VARCHAR(40)   NULL,       -- COMPLETED|REVERSED|MANUAL_REVIEW|IN_PROGRESS
    resolution_note VARCHAR(1000) NULL,

    -- A LEASE, not a lock. An in-memory lock dies with the process and leaves
    -- the case either unclaimable or double-claimed. A lease in the database
    -- expires on its own, so a crashed worker's case is picked up
    -- automatically by the next sweep.
    claimed_by      VARCHAR(100)  NULL,
    claimed_until   TIMESTAMP     NULL,

    closed_at       TIMESTAMP     NULL,
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_recovery_cases PRIMARY KEY (case_id),
    -- One case per transaction. A second detector sweep must not open a
    -- duplicate investigation while the first is still running.
    CONSTRAINT uq_recovery_txn UNIQUE (transaction_id),
    CONSTRAINT fk_recovery_txn FOREIGN KEY (transaction_id)
        REFERENCES transactions (transaction_id)
);

CREATE INDEX idx_recovery_open
    ON recovery_cases (claimed_until)
    WHERE closed_at IS NULL;


-- The output of asking the money-holder what it actually recorded.
CREATE TABLE reconciliation_records (
    reconciliation_id UUID        NOT NULL DEFAULT gen_random_uuid(),
    case_id           UUID        NOT NULL,
    transaction_id    UUID        NOT NULL,
    leg               VARCHAR(20) NOT NULL,   -- DEBIT|CREDIT|REVERSAL
    our_belief        VARCHAR(40) NOT NULL,   -- what the orchestrator thought
    their_record      VARCHAR(40) NOT NULL,   -- what the funds mover reported
    matched           BOOLEAN     NOT NULL,
    raw_response      JSONB       NULL,
    checked_at        TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_reconciliation PRIMARY KEY (reconciliation_id),
    CONSTRAINT fk_recon_case FOREIGN KEY (case_id)
        REFERENCES recovery_cases (case_id)
);

CREATE INDEX idx_recon_txn ON reconciliation_records (transaction_id, checked_at);
