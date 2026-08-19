# Failure Scenarios and Recovery Design

> The specification for the self-healing subsystem. Each scenario states:
> failure → detection → resulting state → recovery decision → recovery action
> → verification → final state.
>
> **The organising question for every scenario is the same:
> "Did money move, and do we know?"** There are only four answers, and they
> determine everything:

| | We know it moved | We do not know |
|---|---|---|
| **Money moved** | act on it | ★ **reconcile before acting** |
| **Money did not move** | fail fast, safe | ★ **reconcile before acting** |

The starred cells are the entire reason this subsystem exists. Any design that
collapses them into "success" or "failure" will eventually create or destroy
money.

---

## Scenario A — Request timed out, but the transaction actually succeeded

*Client sent `POST /payments`, got no response; the payment completed.*

- **Detection.** None needed server-side — the server is consistent. The risk
  is entirely client-side: the client retries.
- **State.** `COMPLETED`.
- **Recovery decision.** The retry carries the same `Idempotency-Key`. The
  unique constraint on `transactions.idempotency_key` rejects the second
  insert; the cached response is replayed.
- **Verification.** Exactly one row, exactly two ledger postings.
- **Final state.** `COMPLETED`, charged once.

**Why the client-supplied key and not a server-generated one:** the server
cannot tell a retry from a genuine second payment of the same amount to the
same payee. Only the client knows its own intent. This is why idempotency keys
are always client-supplied.

---

## Scenario B — Debit succeeded, credit failed (definitively)

- **Detection.** `CreditFailed` received with an explicit, non-retryable bank
  error code.
- **State.** `DEBITED` → `CREDIT_FAILED`.
- **Recovery decision.** **Compensate.** The outcome is *known*, so there is
  nothing to reconcile.
- **Recovery action.** `ReversalRequested` → funds mover credits the payer
  back, keyed on `(transaction_id, REVERSAL)` so a repeat posts nothing.
- **Verification.** Payer's balance equals its pre-payment value; ledger holds
  a debit and a matching reversal credit; payee is untouched.
- **Final state.** `REVERSED`.

**Concept — compensation, not rollback.** The debit is committed and visible;
it cannot be undone. A saga does not roll back, it applies a *semantically
inverse* action, and the ledger retains both postings. That is a feature: the
audit trail shows what happened, not a doctored version of it.

---

## Scenario C — Credit succeeded, but the response was lost ★

*The dangerous one.*

- **Detection.** No `CreditSucceeded` / `CreditFailed` within the credit
  timeout. Detector sweeps for `CREDIT_REQUESTED` older than the threshold.
- **State.** `CREDIT_REQUESTED` → **`UNCERTAIN`**.
- **Recovery decision.** **Reconcile. Never retry, never reverse.**
- **Recovery action.** Ask the funds mover: *"what did you record for
  transaction X?"* — a **query**, not a command, and therefore always safe.
  - mover reports a credit posting → decision `COMPLETE`
  - mover reports no posting → decision `REVERSE`
  - mover unreachable → stay `UNCERTAIN`, retry the query with backoff
  - N queries exhausted → `MANUAL_REVIEW`
- **Verification.** Exactly one credit posting for this transaction id.
- **Final state.** `COMPLETED`, or `REVERSED`, or `MANUAL_REVIEW`.

**What goes wrong without `UNCERTAIN`:**
- treat as failed → reverse → payee keeps the credit *and* payer is refunded →
  **money created**
- treat as succeeded → payee was never credited → **money destroyed**
- blind retry → **double credit**

Three plausible reactions, all wrong. Only "ask what actually happened" is
correct — and it needs a state that means "do not act yet".

---

## Scenario D — Message published but the consumer failed

- **Detection.** Consumer throws; the message is not marked processed.
- **State.** Unchanged — the state change is what *causes* the message
  (outbox), never the reverse.
- **Recovery.** Redelivery with exponential backoff; after N attempts, DLQ +
  alert.
- **Verification.** `processed_events` contains exactly one row for the
  `event_id`.
- **Final state.** Processed once, or DLQ'd with the transaction still in a
  non-terminal state, which the detector then picks up.

**Note (G-07):** the current consumer catches the exception and skips `ack()`,
believing that triggers redelivery. It does not — the container advances and
the offset is simply never committed. The exception must propagate.

---

## Scenario E — Consumer processed the event, acknowledgement failed

- **Detection.** None — this is invisible by design.
- **State.** Correctly updated; the message is redelivered anyway.
- **Recovery.** The second delivery finds the `event_id` in `processed_events`
  and drops it.
- **Final state.** Processed exactly once, in effect.

**Concept.** This is the scenario that proves at-least-once + idempotent
consumption is *equivalent* to exactly-once. There is no acknowledgement
protocol that eliminates this window — only idempotency does.

---

## Scenario F — Database unavailable

- **Detection.** Connection failure; readiness probe fails.
- **State.** Unchanged. Nothing was committed, so nothing is inconsistent.
- **Recovery.** Fail fast, return 503, shed load. On recovery the outbox relay
  drains the backlog and the detector sweeps anything left mid-flight.
- **Final state.** Consistent — because the database being the single source
  of truth means its unavailability causes *unavailability*, not *corruption*.

**Concept.** Correctness over availability, made concrete. The system stops
rather than guesses. A payment system that keeps accepting payments while its
ledger is unreachable is not more available, it is broken with better uptime
metrics.

---

## Scenario G — Downstream funds mover unavailable

- **Detection.** Circuit breaker opens after the failure-rate threshold.
- **State.** Depends on where it broke:
  - before the debit → `FAILED` (safe, nothing moved)
  - after the debit, before the credit reply → **`UNCERTAIN`** (Scenario C)
- **Recovery.** Do not retry while the circuit is open — a retry storm is how
  a degraded dependency becomes a dead one. Queue for recovery; the detector
  re-attempts once the circuit half-opens.
- **Final state.** Resolved via reconciliation when the mover returns.

---

## Scenario H — Duplicate payment request

- **Detection.** Unique constraint violation on `idempotency_key`.
- **State.** Unchanged.
- **Recovery.** Return the original response.
- **Concurrent case.** Two simultaneous requests, same key: both pass the
  read-check, both attempt the insert, exactly one wins. The loser catches
  `DataIntegrityViolationException` and reads the winner's row.
- **Final state.** One transaction.

**Concept.** The database constraint is the guard; the read is only a fast
path. Any check-then-act idempotency scheme has a race window between the
check and the act. `PaymentServiceImpl` already gets this right.

---

## Scenario I — Network partition

- **Detection.** Timeouts on the partitioned side.
- **State.** `UNCERTAIN` for anything in flight.
- **Recovery.** Reconciliation after the partition heals. During the
  partition, refuse to guess.
- **Final state.** Resolved post-heal, or `MANUAL_REVIEW`.

**Concept.** CAP, made concrete for money: during a partition a payment system
chooses **C over A** — it stops accepting, rather than accepting payments it
cannot record consistently.

---

## Scenario J — Service crashes mid-transaction

- **Detection.** Detector sweep finds a non-terminal transaction with no
  progress past its threshold.
- **State.** Whatever committed before the crash — no partial state exists,
  because each saga step is one local ACID transaction.
- **Recovery.**
  - crashed *before* commit → nothing happened; re-drive from the last
    committed state
  - crashed *after* commit, before outbox delivery → relay delivers on restart
  - crashed *after* a funds command, before the reply → `UNCERTAIN` → reconcile
- **Final state.** Deterministic in every case.

**Concept.** This is the payoff of the outbox. Because the state change and
the intent-to-send commit together, there is no crash point that leaves the
system unable to work out what to do next.

---

## Test matrix

| # | Scenario | Test kind | Asserts |
|---|---|---|---|
| A | duplicate key | integration | one txn, two postings, identical response |
| B | credit hard-fail | integration | payer whole, one reversal posting |
| C | credit timeout, credit did happen | integration | `COMPLETED`, **exactly one** credit posting |
| C' | credit timeout, credit did not happen | integration | `REVERSED`, payer whole |
| D | consumer throws | integration | redelivered, then DLQ |
| E | duplicate delivery | unit | second delivery is a no-op |
| F | DB down | integration | 503, no partial state |
| G | mover down | integration | circuit opens, no retry storm |
| H | concurrent duplicates | concurrency | exactly one insert wins |
| I | partition | integration | `UNCERTAIN`, resolves after heal |
| J | crash mid-saga | integration | resumes to a terminal state |
| — | **money conservation** | property | for every terminal txn, `sum(debits) == sum(credits)` |

The last row is the one that matters most: whatever else is true, the ledger
must balance.
