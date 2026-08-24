-- V2 — the append-only audit trail.
--
-- Every state change is an INSERT. There is no UPDATE and no DELETE anywhere
-- in the codebase for this table, and the trigger below makes that structural
-- rather than a matter of discipline. The entity also carries a @PreUpdate
-- guard, so there are two independent layers: application and database.
--
-- Why an audit trail is not optional here: `transactions.current_state` tells
-- you where a payment is. Only this table tells you how it got there -- which
-- is the only thing that can answer "was this customer charged twice, and
-- why?" months later.

CREATE TABLE transaction_events (
    event_id       UUID          NOT NULL DEFAULT gen_random_uuid(),
    transaction_id UUID          NOT NULL,
    from_state     VARCHAR(30)   NOT NULL,
    to_state       VARCHAR(30)   NOT NULL,
    description    VARCHAR(500)  NULL,
    event_payload  JSONB         NULL,
    triggered_by   VARCHAR(100)  NULL,
    occurred_at    TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_transaction_events PRIMARY KEY (event_id),
    CONSTRAINT fk_txn_events_txn FOREIGN KEY (transaction_id)
        REFERENCES transactions (transaction_id)
);

CREATE INDEX idx_events_txn_id ON transaction_events (transaction_id, occurred_at);

CREATE OR REPLACE FUNCTION reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'transaction_events is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_txn_events_immutable
    BEFORE UPDATE OR DELETE ON transaction_events
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
