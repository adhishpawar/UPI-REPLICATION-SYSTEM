-- V1 — the payment record.
--
-- Design notes that matter more than the DDL:
--
--  * amount is NUMERIC(15,2), never FLOAT/DOUBLE. Binary floating point
--    cannot represent 0.1 exactly; a payment system that uses it will
--    eventually be out by a paisa and have no way to explain why.
--
--  * idempotency_key carries a UNIQUE constraint. That constraint IS the
--    duplicate guard. A SELECT-then-INSERT is a check-then-act race: two
--    concurrent requests both read "absent" and both insert. Only the
--    constraint is atomic.
--
--  * version supports optimistic locking. Two writers can legitimately
--    target this row (a saga step and a recovery-driven transition). Without
--    @Version the later write silently overwrites the earlier one.
--
--  * state_deadline_at is when the CURRENT in-flight state stops being
--    plausible. It is not a failure timer -- it is the moment the outcome
--    becomes UNKNOWN, which is a different and much more dangerous thing.

CREATE TABLE transactions (
    transaction_id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    rrn                       VARCHAR(12)   NOT NULL,
    idempotency_key           VARCHAR(100)  NOT NULL,
    idempotency_response      TEXT          NULL,

    payer_vpa                 VARCHAR(100)  NOT NULL,
    payee_vpa                 VARCHAR(100)  NOT NULL,
    payee_account_holder_name VARCHAR(200)  NULL,

    -- Resolved at payee-validation time and snapshotted. A VPA may later be
    -- re-pointed at a different account; this payment must remain explainable.
    payer_account_number      VARCHAR(64)   NULL,
    payee_account_number      VARCHAR(64)   NULL,

    amount                    NUMERIC(15,2) NOT NULL,
    currency                  VARCHAR(3)    NOT NULL DEFAULT 'INR',
    funding_source            VARCHAR(20)   NOT NULL DEFAULT 'BANK',

    current_state             VARCHAR(30)   NOT NULL,
    version                   BIGINT        NOT NULL DEFAULT 0,

    payer_user_id             UUID          NOT NULL,
    device_id                 VARCHAR(255)  NULL,
    remarks                   VARCHAR(500)  NULL,
    trace_id                  VARCHAR(64)   NULL,

    bank_debit_reference      VARCHAR(100)  NULL,
    bank_credit_reference     VARCHAR(100)  NULL,
    failure_reason            VARCHAR(500)  NULL,

    -- Deadline for the current non-terminal state. NULL once terminal.
    state_deadline_at         TIMESTAMP     NULL,
    uncertain_since           TIMESTAMP     NULL,

    -- Demo-only. Names a deterministic failure scenario to inject.
    -- Never set by normal traffic. See docs/testing/failure-scenarios.md.
    demo_scenario             VARCHAR(40)   NULL,

    initiated_at              TIMESTAMP     NOT NULL DEFAULT NOW(),
    completed_at              TIMESTAMP     NULL,
    updated_at                TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_transactions       PRIMARY KEY (transaction_id),
    CONSTRAINT uq_txn_rrn            UNIQUE (rrn),
    CONSTRAINT uq_txn_idempotency    UNIQUE (idempotency_key),
    CONSTRAINT chk_txn_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_txn_funding_source  CHECK (funding_source IN ('BANK','WALLET')),
    CONSTRAINT chk_txn_no_self_pay     CHECK (payer_vpa <> payee_vpa)
);

CREATE INDEX idx_txn_payer_user  ON transactions (payer_user_id, initiated_at DESC);
CREATE INDEX idx_txn_state       ON transactions (current_state);

-- The detector's sweep query. A partial index keeps it cheap: completed
-- transactions vastly outnumber in-flight ones and must not be scanned.
CREATE INDEX idx_txn_deadline
    ON transactions (state_deadline_at)
    WHERE state_deadline_at IS NOT NULL;

COMMENT ON COLUMN transactions.state_deadline_at IS
  'When the current in-flight state stops being plausible. Breaching this makes the outcome UNKNOWN, not FAILED.';
