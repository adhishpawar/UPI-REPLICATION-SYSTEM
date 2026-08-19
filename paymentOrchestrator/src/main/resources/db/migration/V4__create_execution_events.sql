-- V4 - the execution trace.
--
-- Every meaningful step the platform takes appends a row here: inbound HTTP,
-- outbound HTTP, DB writes, state transitions, event publish/consume, and
-- recovery actions, all correlated by trace_id and transaction_id.
--
-- This table serves two requirements that turn out to be the same
-- requirement:
--   1. operability - after an incident, replay exactly what happened
--   2. the showcase - stream real execution to a UI
--
-- Because the showcase renders rows the backend wrote while doing real work,
-- it cannot lie. Delete the showcase and the rows are still here.

CREATE TABLE execution_events (
    id             BIGSERIAL     NOT NULL,
    trace_id       VARCHAR(64)   NOT NULL,
    transaction_id UUID          NULL,
    seq            INT           NOT NULL,   -- ordering within a trace
    component      VARCHAR(60)   NOT NULL,   -- payment-orchestrator, bank-service
    operation      VARCHAR(120)  NOT NULL,   -- PaymentService.initiatePayment
    kind           VARCHAR(20)   NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    latency_ms     INT           NULL,
    detail         JSONB         NULL,
    message        VARCHAR(500)  NULL,
    at             TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_execution_events PRIMARY KEY (id),
    CONSTRAINT chk_exec_kind CHECK (kind IN
        ('HTTP_IN','HTTP_OUT','DB','STATE','EVENT_PUB','EVENT_CONS','RECOVERY','LOG')),
    CONSTRAINT chk_exec_status CHECK (status IN
        ('STARTED','OK','FAILED','TIMEOUT','SKIPPED'))
);

CREATE INDEX idx_exec_trace ON execution_events (trace_id, seq);
CREATE INDEX idx_exec_txn   ON execution_events (transaction_id, id);
CREATE INDEX idx_exec_at    ON execution_events (id DESC);
