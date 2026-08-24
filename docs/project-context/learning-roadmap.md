# Learning Roadmap

> Ordered by *what teaches the most, soonest*, subject to dependencies.
> Each milestone leaves the platform runnable.
> Tags: `[PHASE 1 - BUILD NOW]` · `[PHASE 2 - BUILD LATER]` · `[PRODUCTION CONCEPT]` · `[OPTIONAL]`

## The ordering principle

Build a **thin vertical slice that moves real money end to end**, then make it
fail, then make it heal. Do not build breadth (more services) before depth
(one path that is actually correct). A platform with eight services and no
completable payment teaches less than one process that can move ₹500 and prove
it did.

---

## M0 — Make the orchestrator start `[PHASE 1]` · ~45 min

**Goal.** `paymentOrchestrator` boots, `/actuator/health` is UP, `payment_db`
exists with real migrations.

Fixes G-02, G-03, G-08, G-09, G-10, G-11.
Files: `application.yml`, `SecurityConfig`, `WebClientConfig`,
`V1__create_transactions.sql`, `V2__create_transaction_events.sql`.

**DoD.** Service starts against Postgres; Flyway reports migrations applied;
`POST /api/v1/payments` returns a validation error rather than a 500 or a
Basic-auth challenge.

**Concept.** Why `ddl-auto: validate` + Flyway is the only defensible
combination for anything holding money: the schema is a reviewed, versioned
artefact, not a side effect of an entity class.

---

## M1 — Transactional outbox `[PHASE 1]` · ~1 h

**Goal.** State changes and outbound messages commit atomically.

`outbox_messages` table; `EventPublisher` port; `OutboxRelay` (scheduled
poller); `InProcessRelay`; `processed_events` for consumer idempotency.
Fixes G-04.

**DoD.** Kill the process between commit and delivery — the message is still
delivered on restart. A test proves it.

**Concept.** Dual-write. Why publish-before-commit invents money and
commit-before-publish loses payments. Why "exactly once" is a property of
*processing*, never of *delivery*.

---

## M2 — Funds movers: debit and credit `[PHASE 1]` · ~1.5 h

**Goal.** A `FundsMover` port with a bank implementation that debits and
credits a real ledger with real row locks.

Fixes G-17, G-18. Reuses `bank-service`'s locking approach.
Command handlers consume `DebitRequested` / `CreditRequested` and emit
outcomes through the outbox.

**DoD.** Payer's balance drops by exactly the amount; payee's rises by exactly
the amount; two ledger rows exist; replaying the same command posts nothing
and returns the original result.

**Concept.** Pessimistic vs optimistic locking, and why a ledger without a
uniqueness constraint per `(transaction_id, direction)` is not a ledger.

---

## M3 — Complete the saga `[PHASE 1]` · ~1 h

**Goal.** `POST /payments` reaches `COMPLETED`.

Fixes G-01, G-12, G-13. Adds `@Version` to `Transaction`.

**DoD.** End-to-end: initiate → 202 → poll → `COMPLETED`; balances correct;
`transaction_events` shows every transition with correct `from`/`to`.

**Concept.** Saga vs distributed transaction. Why a payment is a sequence of
locally-atomic steps with compensations, not one big ACID transaction.

---

## M4 — Execution event stream `[PHASE 1]` · ~1 h

**Goal.** Every step of a payment is recorded and streamable.

`execution_events` table + `ExecutionRecorder` + `GET /api/v1/execution/stream`
(SSE) + `GET /api/v1/execution/{transactionId}` (replay).

**DoD.** One payment produces an ordered, correlated trace of HTTP calls,
state transitions, DB writes, and event publish/consume — all real.

**Concept.** Correlation IDs and why observability is a design property, not
an add-on. This is also the honest foundation for the showcase.

---

## M5 — Failure injection + `UNCERTAIN` `[PHASE 1]` · ~1 h

**Goal.** Deterministically reproduce the dangerous failures.

Adds `UNCERTAIN`, `RECONCILING`, `MANUAL_REVIEW` (D-007). A
`FailureInjector` active only under a demo profile, driven by an explicit
scenario parameter — never random, never in normal execution.

Scenarios: credit timeout (outcome unknown) · credit hard-fail (compensate) ·
transient failure (retry succeeds) · consumer crash mid-processing.

**DoD.** Each scenario reproducibly parks the transaction in the right state.

**Concept.** Timeout ≠ failure. The most expensive assumption in payments.

---

## M6 — Self-healing engine `[PHASE 1]` · ~1.5 h

**Goal.** Uncertain transactions resolve themselves, safely.

Detector (sweeps for stale non-terminal transactions) → Classifier (is the
outcome unknown, known-failed, or transient?) → Recovery Orchestrator →
Reconciler (asks the funds mover what it actually recorded) → emits
`RecoveryDecision` → orchestrator applies it through the state machine
(D-001). Lease-based claiming so two sweeps cannot both act.

**DoD.** A transaction stuck in `UNCERTAIN` where the credit *did* succeed
resolves to `COMPLETED` **without a second credit**. One where it did not
resolves to `REVERSED` with exactly one reversal. Balances verified both ways.

**Concept.** Reconcile-then-decide. Why the reconciler asks the money-holder
rather than retrying blindly, and why every recovery action must be idempotent
because recovery itself can crash and re-run.

---

## M7 — Backend showcase `[PHASE 1]` · ~1.5 h

**Goal.** One page, three use cases, real execution.

`backend-showcase/` — static HTML/JS, `jwebserver`, SSE. Shows service calls
with method/endpoint/latency/status, the state machine advancing, live
structured logs, and the recovery timeline. No hard-coded animation: every
pixel is driven by an `ExecutionEvent` the backend actually wrote.

**DoD.** Run a payment, watch it execute. Run the failure scenario, watch
detection → classification → reconciliation → final state. Stop the backend
and the page visibly goes dead — proving it was never faking.

---

## M8 — Test the money `[PHASE 1]` · ~1 h

Same idempotency key twice · two concurrent payments on one account · timeout
after debit · duplicate event delivery · consumer restart mid-batch · recovery
retry · reconciliation mismatch.

**Concept.** In payment systems the tests *are* the specification. "It worked
when I clicked it" is not evidence.

---

## Beyond the sprint

| | Milestone | Tag |
|---|---|---|
| M9 | Wallet as second `FundsMover`; wallet-to-wallet | `[PHASE 1 - LATER]` |
| M10 | Extract bank adapters; swap `InProcessRelay` → `KafkaRelay` | `[PHASE 2]` |
| M11 | JWT validation via psp-service JWKS (Q-5) | `[PHASE 2]` |
| M12 | Micrometer metrics + Zipkin tracing | `[PHASE 2]` |
| M13 | Testcontainers integration tests | `[PHASE 2]` |
| M14 | API gateway, notification service, QR service | `[PHASE 2]` |
| M15 | Containerisation + CI/CD | `[PHASE 2]` |
| M16 | AI/ML — see below | `[PHASE 2]` |

---

## AI/ML roadmap `[PHASE 2 - BUILD LATER]`

Ranked by *learning value per unit of risk*. Nothing here is built until
Phase 1 is complete, because **every one of these needs data that only a
working system can produce.**

### 1. Failure classification — recommended first
**Problem.** When a payment fails, deciding whether the cause is transient
(retry), permanent (fail fast), or unknown (reconcile) currently needs a
hand-written rule per error code. New codes appear constantly.
**Why rules fall short.** They do — *eventually*, and only at scale. Be honest:
for a learning project the rule engine is probably sufficient. The ML version
is worth building because it teaches the *shape* of a classifier in a
correctness-critical path, not because rules are failing.
**Data.** `execution_events` + `transaction_events` + outcomes. Available for
free once M4 lands.
**Model.** Multiclass classifier (logistic regression / gradient boosting) on
tabular features. Small enough to train and run locally.
**Inference.** In-process, <10 ms, on the failure path.
**Failure behaviour.** Model unavailable or low confidence → **fall back to
the rule engine**. Never block a payment on a model.
**Advisory or autonomous.** Advisory. It may propose `RETRY` but never execute
a financial operation on its own.

### 2. Anomaly detection on transaction patterns
Unsupervised (isolation forest) over amount/time/velocity/counterparty
features. Genuinely hard to express as rules — that is the honest
justification. Advisory: flags for review, never blocks. `[PHASE 2]`

### 3. Applied AI for incident diagnosis
An LLM summarising an execution trace into a plain-language incident
narrative. Read-only, off the critical path, no financial authority.
Highest immediate usefulness, lowest risk. `[PHASE 2]` `[OPTIONAL]`

### 4. Fraud scoring
Deferred deliberately. The self-healing design's `FraudGuardAgent` needs
labelled fraud data that a learning project cannot obtain. Building it on
synthetic labels teaches the *plumbing* of a scoring service while teaching
nothing true about fraud — and risks the impression that a real fraud control
exists. `[PHASE 2]` with clearly-labelled synthetic data, or not at all.

### Rejected outright
**Predictive Failure Radar** (from the self-healing design) as specified —
predicting failure "before TTL breach" from time-of-day and bank trust score.
No causal mechanism, no ground truth available here, and its output would
influence money movement. This is the category of ML that looks impressive in
a design document and is indefensible in a payment system.
