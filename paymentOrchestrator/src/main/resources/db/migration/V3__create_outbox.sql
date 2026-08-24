-- V3 - the messaging substrate (ADR-0001).
--
-- THE PROBLEM THIS SOLVES
--
-- A database commit and a message publish are two independent systems. They
-- cannot be made atomic without a distributed transaction. So there are two
-- possible orderings, and both are broken:
--
--   publish then commit  ->  broker accepts "DebitRequested", transaction
--                            rolls back. A debit is in flight for a payment
--                            that does not exist.  MONEY MOVES FOR NOTHING.
--
--   commit then publish  ->  state is durable, process dies before
--                            publishing. The payment is stranded forever with
--                            no command ever issued.  A PAYMENT SILENTLY DIES.
--
-- The outbox removes the choice: the message row is written in the SAME
-- transaction as the state change. Either both exist or neither does. A
-- separate relay then delivers rows and marks them published.
--
-- The relay can crash after delivering but before marking, so delivery is
-- AT-LEAST-ONCE and every consumer must be idempotent. That is what
-- processed_events is for.
--
-- Exactly-once DELIVERY does not exist across a system boundary. What exists
-- is at-least-once delivery plus idempotent processing, which is
-- observationally equivalent. Internalising that equivalence is the point.

CREATE TABLE outbox_messages (
    message_id      UUID          NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50)   NOT NULL,   -- e.g. Transaction
    aggregate_id    UUID          NOT NULL,   -- the transaction_id
    event_type      VARCHAR(80)   NOT NULL,   -- e.g. DebitRequested
    payload         JSONB         NOT NULL,
    trace_id        VARCHAR(64)   NULL,

    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMP     NULL,       -- NULL means still owed
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000) NULL,
    next_attempt_at TIMESTAMP     NOT NULL DEFAULT NOW(),
    dead_lettered   BOOLEAN       NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_outbox PRIMARY KEY (message_id)
);

-- The relay's only query. Partial index: published rows accumulate forever
-- and must never be scanned.
CREATE INDEX idx_outbox_unpublished
    ON outbox_messages (next_attempt_at)
    WHERE published_at IS NULL AND dead_lettered = FALSE;

CREATE INDEX idx_outbox_aggregate ON outbox_messages (aggregate_id, created_at);


-- Consumer-side idempotency. A consumer records the message_id it has
-- processed in the SAME transaction as its side effect. A redelivery finds
-- the row and drops the message.
--
-- This is the other half of at-least-once. Without it, redelivery is a
-- duplicate debit.
CREATE TABLE processed_events (
    message_id   UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_processed_events PRIMARY KEY (message_id, consumer)
);

CREATE INDEX idx_processed_at ON processed_events (processed_at);
