# Architecture Context

> How the platform is built, and why. Read `domain-context.md` first.
> Every recommendation is tagged:
> `[PHASE 1 - BUILD NOW]` · `[PHASE 2 - BUILD LATER]` · `[PRODUCTION CONCEPT]` · `[OPTIONAL]`

## 1. Architecture as inferred from the existing code

This is what the repository actually implements today, not what the documents
describe.

```
                       (no API gateway exists)
                                |
        +-----------------------+------------------------+
        |                       |                        |
  psp-service:8082        vpa-service:8081      bank-service:8081 (port clash)
  register/login/JWT      register/resolve       accounts / debit / credit
  RS256 + JWKS            Flyway, validate       SELECT..FOR UPDATE + ledger
  pspdb                   vpa_db                 bankdb
        |                       ^
        |                       | HTTP GET /api/v1/vpa/{addr}   (WebClient, CB+Retry)
        |                       |
        |             paymentOrchestrator  (NOT RUNNABLE - no config)
        |             POST /api/v1/payments -> 202
        |             SagaOrchestrator + TransactionStateMachine
        |             publishes: payment.debit.requested
        |                        payment.credit.requested
        |                        payment.reversal.requested
        |             consumes:  payment.debit.success/failed
        |                        payment.credit.success/failed
        |                        payment.reversal.success
        |                                |
        |                                v
        |                    +-----------------------+
        |                    |   *** NOTHING HERE ***|
        |                    | no bank-debit-adapter |
        |                    | no bank-credit-adapter|
        |                    | Kafka not running     |
        |                    +-----------------------+
        |
  user-service:8080 (legacy, ddl-auto=create, superseded by psp-service)
```

**The decisive structural fact:** the saga publishes commands that nobody
consumes, and waits for replies nobody produces. Every payment therefore stops
at `PAYEE_VALIDATED` forever. The platform has a head and no body.

Separately, on the branch `impl/paymentOrchestrator` there are three
correctness defects that would break the happy path even with adapters
present — see `known-gaps.md` G-01..G-03.

## 2. Target architecture V1 — Modular monolith + real adapters `[PHASE 1 - BUILD NOW]`

### Why a modular monolith and not the 8 microservices in the master plan

The master plan's 8-service split is a good *production* target and a poor
*learning-phase starting point*, for three concrete reasons:

1. **The interesting problems are not distribution problems.** Idempotency,
   the saga, the outbox, `UNCERTAIN`-state reconciliation, and safe retry are
   all fully expressible inside one process. Splitting them across eight
   processes adds deployment, config, and debugging cost without adding a
   single new concept to learn.
2. **The environment cannot run them.** Kafka and Docker are both down; there
   is exactly one working dependency (PostgreSQL). An architecture that cannot
   be started is an architecture that cannot be learned from.
3. **Bounded contexts are cheap to keep, processes are expensive.** Enforcing
   module boundaries inside one build gives 90% of the architectural benefit
   at 10% of the operational cost, and leaves the extraction path open.

The boundaries are kept *real* — separate modules, no cross-module entity
imports, communication only through ports — so extraction later is mechanical.

### V1 shape

```
  showcase (static HTML/JS, separate origin)
        |  REST + SSE
        v
+-------------------------------------------------------------+
|  upi-platform  (one Spring Boot process, module boundaries)  |
|                                                             |
|  web/         PaymentController · SSE stream · error mapper  |
|  payment/     PaymentService · SagaOrchestrator              |
|               TransactionStateMachine (single writer)        |
|  ports/       FundsMover · VpaDirectory · EventPublisher     |
|  bank/        BankFundsMover  -> bank ledger (existing code) |
|  wallet/      WalletFundsMover -> wallet ledger  [P1 later]  |
|  recovery/    Detector · Classifier · Orchestrator           |
|               Reconciler · RecoveryDecision emitter          |
|  outbox/      outbox table + relay (the messaging substrate) |
|  observ/      ExecutionEvent recorder + SSE fan-out          |
+-------------------------------------------------------------+
        |
        v  PostgreSQL 17 (the only required infrastructure)
```

Existing `vpa-service` and `psp-service` keep running as **separate
processes** — they are already working, already deployed independently, and
already demonstrate real cross-service HTTP with circuit breakers. Rewriting
them into the monolith would violate "preserve working code" and would delete
the one genuinely distributed interaction the platform has.

> **Preserved from your design, not replaced:** the saga, the hand-rolled
> `EnumMap`/`EnumSet` state machine, the append-only `transaction_events`
> table with its `@PreUpdate` guard, the `SELECT ... FOR UPDATE` balance
> locking, the RRN generator, and the DB-unique-constraint idempotency guard
> are all *correct instincts* and are all kept.

## 3. The messaging substrate: transactional outbox `[PHASE 1 - BUILD NOW]`

### The problem, concretely

`SagaOrchestrator.validatePayee()` today does:

```java
@Transactional
public void validatePayee(UUID id) {
    txn.setCurrentState(PAYEE_VALIDATED);
    transactionRepository.save(txn);      // (1) not yet committed
    eventProducer.publishDebitRequest(txn); // (2) fire-and-forget to Kafka
}                                          // (3) commit happens HERE
```

The code comments call this "the outbox pattern". It is not — and the gap is
worth understanding precisely, because it is the single most common
distributed-systems bug in payment code.

A database commit and a message publish are **two separate systems**. There is
no way to make them atomic without a distributed transaction. So there are
exactly two orderings, and both are broken:

- **Publish before commit** (what the code does): the broker accepts
  `DebitRequested`, then the transaction rolls back. A debit is now in flight
  for a payment that does not exist. **Money moves for nothing.**
- **Commit before publish**: the state is saved, then the process crashes
  before publishing. The payment is stuck at `PAYEE_VALIDATED` forever, with
  no debit ever requested. **A payment silently dies.**

Additionally `KafkaTemplate.send()` is asynchronous — the `whenComplete`
callback logs the failure and nothing else. A dropped publish is currently
invisible beyond a log line.

### The fix

Write the message **into the same database transaction as the state change**:

```java
@Transactional
public void validatePayee(UUID id) {
    txn.setCurrentState(PAYEE_VALIDATED);
    transactionRepository.save(txn);
    outbox.append(DebitRequested.of(txn));   // INSERT into outbox_messages
}   // ONE commit: either both rows exist, or neither does
```

A separate **relay** then polls `outbox_messages WHERE published_at IS NULL`
and delivers each row. If delivery fails, the row stays; it is retried. If
delivery succeeds but the relay crashes before marking it published, the
message is delivered twice — which is why **every consumer must be
idempotent**. This is at-least-once delivery, and it is the honest guarantee.

Exactly-once delivery does not exist across a system boundary. What does exist
is *at-least-once delivery plus idempotent processing*, which is
observationally equivalent to exactly-once. That equivalence is the thing
worth internalising.

### Why this specific choice matters for this project

The outbox is behind an `EventPublisher` port with two implementations:

| Impl | Sink | Requires |
|---|---|---|
| `InProcessRelay` | direct call to in-process consumers | **Postgres only** |
| `KafkaRelay` | `KafkaTemplate` | Kafka |

The *semantics are identical* — same at-least-once guarantee, same duplicate
delivery, same idempotency requirement. Only the transport differs. So the
whole platform runs today on the one dependency that is actually up, and
switching to Kafka later is a bean swap, not a redesign. That is the payoff of
putting messaging behind a port instead of scattering `KafkaTemplate` calls
through the saga.

## 4. Idempotency architecture `[PHASE 1 - BUILD NOW]`

Three distinct layers, currently conflated:

| Layer | Key | Stored where | Retained | Duplicate behaviour |
|---|---|---|---|---|
| **API** | client `Idempotency-Key` header | `transactions.idempotency_key` UNIQUE | life of txn (prod: 24-48 h) | replay the original 202 response |
| **Funds movement** | `transaction_id` + `DEBIT`/`CREDIT` | ledger unique constraint | forever (it is the ledger) | return the original posting, do not post again |
| **Event consumption** | `event_id` | `processed_events` table | 7 days | drop silently |

Concurrency rule: **the unique constraint is the guard, not the read.**
`findByIdempotencyKey()` followed by `save()` is a check-then-act race — two
concurrent requests both read "absent" and both insert. The correct pattern is
to attempt the insert and catch `DataIntegrityViolationException`. The
existing `PaymentServiceImpl` already does this — that instinct is right and
is preserved. `IdempotencyFilter`'s read-first check is a fast path only, and
must never be the sole guard.

## 5. Balance: materialised, ledger-derived, never cached as truth `[PHASE 1 - BUILD NOW]`

Four options, and why the third wins:

| Option | Read cost | Correctness | Verdict |
|---|---|---|---|
| Computed from ledger every read | O(n) rows | perfect | too slow past a few thousand postings |
| Materialised only (no ledger) | O(1) | unauditable — "why is it 4200?" has no answer | **rejected: a balance with no ledger is a number, not money** |
| **Materialised + ledger, reconciled** | O(1) | auditable and verifiable | **chosen** |
| Redis-cached as source of truth | O(1) | cache eviction loses money | **rejected outright** |

`bank-service` already implements the chosen model: `balance_after` on every
ledger row plus a materialised `balance` on the account, both written inside
one `@Transactional` block under `SELECT ... FOR UPDATE`. That is correct and
is preserved.

What is missing is the **reconciliation check** that makes materialisation
safe: `SUM(credits) - SUM(debits)` per account must equal the stored balance.
Recovery runs this. If it ever disagrees, the ledger wins and a mismatch is
raised — because the ledger is append-only and the balance is not.

Redis `[PRODUCTION CONCEPT]`: legitimate for *read-side* balance display under
load, never as the value a debit decision is made against.

## 6. Concurrency `[PHASE 1 - BUILD NOW]`

- **Balance mutation**: pessimistic row lock (`SELECT ... FOR UPDATE`) — the
  correct choice for a hot row where conflicts are expected and retries are
  expensive. Already implemented in `bank-service`.
- **Transaction state**: optimistic locking (`@Version`) — the correct choice
  where two writers (saga step vs. recovery-driven transition) rarely collide
  but must never silently overwrite each other. **Currently missing.** Without
  it, a recovery decision and a late bank callback can both write
  `current_state` and the last writer wins arbitrarily.
- **Recovery attempts**: a claim, not a lock. `UPDATE recovery_cases SET
  claimed_by=?, claimed_until=? WHERE id=? AND (claimed_until IS NULL OR
  claimed_until < now())` — a lease that survives process death, unlike an
  in-memory lock. Redis locks `[PHASE 2]` add nothing here until there are
  multiple instances.

## 7. Failure handling `[PHASE 1 - BUILD NOW]`

Retry policy, stated as a rule rather than a config value:

> **Retry only operations that are safe to repeat, and only when the outcome
> is genuinely unknown.**

| Situation | Retry? | Why |
|---|---|---|
| VPA lookup timeout | **yes** | read-only, no side effect |
| Debit request, no response | **no — reconcile first** | the debit may have succeeded |
| Debit returned explicit `INSUFFICIENT_FUNDS` | **no** | a known answer; retrying cannot change it |
| Credit request, no response | **no — reconcile first** | the credit may have succeeded |
| Outbox publish failed | **yes, forever** | publishing is idempotent by `event_id` |
| Consumer threw | **yes, with backoff, then DLQ** | may be transient |

"Reconcile first" means: ask the money-holder what it recorded for this
`transaction_id`, and only then decide. Blind retry of a debit is how money
gets created.

Circuit breakers, bulkheads, and rate limits `[PHASE 1 for VPA client, already
present]` `[PHASE 2 for the rest]`.

## 8. Observability `[PHASE 1 - BUILD NOW]`

The showcase requirement ("show what is really happening") and the operability
requirement are the same requirement. One mechanism serves both:

Every meaningful step appends an **ExecutionEvent** row:
`trace_id, transaction_id, seq, component, operation, kind (HTTP_IN /
HTTP_OUT / DB / STATE / EVENT_PUB / EVENT_CONS / RECOVERY), status,
latency_ms, detail, at`.

- persisted → replayable after the fact, testable, and the audit trail
- fanned out over SSE → the showcase is a *live view of real execution*, not
  an animation

This is why the showcase can be honest: it renders rows the backend wrote
while doing real work. Delete the showcase and the rows are still there.

`[PHASE 2]` Micrometer counters/timers, `[PHASE 2]` Zipkin via the
already-declared `micrometer-tracing-bridge-brave` dependency.

## 9. Architecture V2 and V3

### V2 — Extract along the real seams `[PHASE 2 - BUILD LATER]`
Trigger: a module needs independent scaling or independent failure isolation.
Extraction order, easiest and most valuable first:

1. **Bank adapters** (debit / credit) — clean async boundary, already
   command/reply shaped, and separates "the money-holder" from "the
   orchestrator", which is the real-world boundary.
2. **Recovery** — different runtime profile (scheduled sweeps, long-running),
   genuinely benefits from independent deployment.
3. **Wallet** — only once it has its own load characteristics.

Switch `EventPublisher` to `KafkaRelay` at this point. Nothing else changes —
that is the test of whether the port was drawn correctly.

### V3 — Production shape `[PRODUCTION CONCEPT]`
API gateway with auth offload; per-service databases; Kafka with schema
registry and DLQs; Redis for read-side caching and distributed leases;
Kubernetes with HPA; OpenTelemetry end to end; blue/green deploys; automated
reconciliation against real bank statements; segregated audit storage.

**Do not build V3.** It is written down so V1 decisions can be checked against
where they lead.

## 10. Frontend separation rules (binding)

```
              CORE BACKEND (Spring Boot)
                       |
        REST / SSE (contracts only, no shared code)
                       |
        +--------------+----------------+
        |                               |
  BACKEND SHOWCASE                FUTURE CORE FE
  backend-showcase/               branch: future/frontend
  build now                       NOT NOW
  static HTML+JS, no build step   product UI
  deletable without breaking      must not depend on
  the backend                     showcase-specific APIs
```

- Core Backend must not import showcase or frontend code, contain UI logic, or
  depend on frontend libraries.
- The showcase must not import backend classes or read the backend database.
  It consumes the public API and the SSE stream, nothing else.
- No Node/npm is installed on this machine, which conveniently forces the
  right answer: **vanilla HTML/CSS/JS, no build step**, served by the JDK's
  own `jwebserver`. The only backend accommodation is a CORS allowance for the
  showcase origin — a normal backend concern, not a coupling.
