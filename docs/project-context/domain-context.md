# Domain Context — Unified Model Across the Three Systems

> Derived from existing code and design documents, not assumed.
> Where the sources disagree, the conflict is stated rather than resolved
> silently. Unresolved items are marked **UNKNOWN**.

## 1. The vocabulary problem

The three systems each invented their own words for the same things. Before
any unification, the collisions must be named:

| Concept | Core UPI says | Wallet doc says | Self-healing says |
|---|---|---|---|
| the money movement | `Transaction` (`transactions`) | `Transaction` (`transactions`) | `Transaction` (`transactions`) |
| its identity | `transactionId` UUID + `rrn` | `txn_id` BIGINT + `txn_ref` | `TransactionId` VO |
| duplicate guard | `idempotency_key` | `txn_ref` | `Fingerprint` (DNA hash) |
| lifecycle | 12 states, orchestrator-owned | 4 states | 11 states, recovery-owned |
| audit | `transaction_events` (append-only) | `outbox_events` | `transaction_audit_log` |
| funding source | bank account via VPA | wallet balance | bank account |

**Three different `Transaction` tables with three different lifecycles is the
single biggest structural risk in this platform.** Two systems both claim to
own transaction state (`paymentOrchestrator` and `upi-recovery`). If both are
allowed to write `status`, the system has no source of truth for whether money
moved. Resolving this is Decision D-001 (see `decision-log.md`).

## 2. Recommended ownership model

The rule applied: **the component that can cause a state change owns that
state; everyone else observes it.**

| Aggregate | Owner | Rationale |
|---|---|---|
| **User / credentials** | PSP Service | It authenticates. Nothing else may hold MPIN or issue tokens. |
| **VPA to account mapping** | VPA Service | Leaf service, no upstream deps, pure directory. |
| **Payment / Transaction** | Payment Orchestrator | It is the only component that *decides* what happens next in the saga. |
| **Transaction state** | Payment Orchestrator, **exclusively** | Two writers = no source of truth. Recovery *requests* transitions; it does not perform them. |
| **Account balance** | Funding source (bank adapter **or** wallet) | Whoever holds the money holds the balance. |
| **Ledger (double-entry)** | Same component as the balance | A balance not derivable from its own ledger is unauditable. |
| **Payment attempt** | Payment Orchestrator | An attempt is a child of a payment. |
| **Failure / Recovery case** | Recovery Service | It owns *its* decision record, not the payment's state. |
| **Reconciliation record** | Recovery Service | Its output artefact. |
| **Events** | Producer owns the schema; consumers own their idempotency | Standard event-ownership rule. |

**The critical inversion vs. the original self-healing design:** its
`architecture.md` invariant #4 says agents publish to Kafka and a persistence
consumer writes the DB. Combined with the Payment Orchestrator also writing
`transactions.current_state`, that produces two writers. The corrected rule:

> **Recovery never writes payment state. Recovery emits a `RecoveryDecision`;
> the Payment Orchestrator applies it through the same state machine that
> guards every other transition.**

This preserves the self-healing design's *own* strongest invariant ("the state
machine owns state") by making it true across systems, not just within one.

## 3. Core aggregates

### Payment (Payment Orchestrator)
The user's *intent* to move money. Immutable once created: payer, payee,
amount, currency, remarks, idempotency key, RRN, device, initiating user.

### Transaction / PaymentAttempt
The *execution* of that intent. Carries `current_state`, bank references,
failure reason, timestamps.

> **Model note (currently conflated):** the existing `Transaction` entity is
> both intent and execution. That is acceptable for Phase 1 — UPI has no
> "retry the same payment as a new attempt" semantics — but it must be split
> the moment recovery is allowed to re-attempt, because otherwise "which
> attempt failed?" has no answer. Tracked as a Phase-2 refactor.

### Ledger entry
A single-sided posting: account, direction (DEBIT/CREDIT), amount,
`transaction_id`, `balance_after`, timestamp. **Append-only.**
`bank-service` already implements this shape correctly.

### Balance
Materialised in the account row, derived from and reconcilable against the
ledger, never cached as the source of truth. Reasoning in
`architecture-context.md`.

### Failure / RecoveryCase (Recovery Service)
`transaction_id`, detected-at, classification, chosen strategy, attempts,
outcome, verification result. Owns *no* payment state.

### Reconciliation record
The comparison of what the orchestrator believes against what the money-holder
reports, plus the resulting delta and the action taken.

## 4. Where the wallet fits

The wallet is **not** a parallel payment system. It is *another funding
source* behind the same payment saga:

```
Payment Orchestrator
      |
      +-- funding source = BANK   --> bank adapter   --> bank ledger
      +-- funding source = WALLET --> wallet service --> wallet ledger
```

This is the single most consequential domain decision available. Modelling
the wallet as "a second payment system" duplicates the saga, the state
machine, idempotency, and recovery — and doubles every correctness bug.
Modelling it as a **funding source behind one `FundsMover` port** means the
wallet inherits, for free: the saga, the state machine, idempotency,
reconciliation, and self-healing. See D-003 in `decision-log.md`.

## 5. Canonical state machine (recommended)

Merges the orchestrator's 12 states with the recovery system's uncertainty
states. The orchestrator's states describe *what the system did*; the recovery
states describe *what the system does not know*.

```
INITIATED
   |
   +--> PAYEE_VALIDATED
           |
           +--> DEBIT_REQUESTED
                   |
                   +--> DEBIT_FAILED   [terminal]  (no money moved - safe)
                   |
                   +--> DEBITED                    <== money has left the payer
                           |
                           +--> CREDIT_REQUESTED
                                   |
                                   +--> CREDITED --> COMPLETED  [terminal]
                                   |
                                   +--> CREDIT_FAILED
                                   |       +--> REVERSAL_INITIATED
                                   |               +--> REVERSED        [terminal]
                                   |               +--> REVERSAL_FAILED
                                   |                       +--> MANUAL_REVIEW [terminal]
                                   |
                                   +--> UNCERTAIN            <== *** the missing state
                                           |
                                           +--> RECONCILING
                                                   +--> COMPLETED       [terminal]
                                                   +--> REVERSAL_INITIATED ...
                                                   +--> MANUAL_REVIEW   [terminal]

INITIATED / PAYEE_VALIDATED --> FAILED [terminal]  (no money moved - safe)
```

### The one state everything hinges on: `UNCERTAIN`

The existing state machine has no way to express *"we asked the bank to
credit, and we do not know what happened."* It can only express success or
failure. But the whole reason UPI recovery exists is that **a timeout is not a
failure** — a timed-out credit may well have succeeded.

Without `UNCERTAIN`, a timeout must be recorded as either:

- `CREDIT_FAILED` — triggers a reversal. If the credit *had* succeeded, the
  payee is paid **and** the payer is refunded: **money is created**.
- `CREDITED` — if the credit had actually failed, the payer is debited and
  nobody is paid: **money disappears**.

Both violate the invariants in `project-overview.md`. `UNCERTAIN` +
`RECONCILING` is the state pair that makes "ask the money-holder what really
happened before deciding" representable. **This is the domain justification
for the entire self-healing system**, and it is why System 3 is not a bolt-on:
it completes System 1's state machine.

## 6. Event model (derived)

| Event | Producer | Consumers | Meaning |
|---|---|---|---|
| `PaymentInitiated` | Orchestrator | showcase, notification | intent recorded |
| `PayeeValidated` | Orchestrator | showcase | payee resolved |
| `DebitRequested` | Orchestrator | funds mover (bank/wallet) | command |
| `DebitSucceeded` / `DebitFailed` | funds mover | Orchestrator | outcome |
| `CreditRequested` | Orchestrator | funds mover | command |
| `CreditSucceeded` / `CreditFailed` | funds mover | Orchestrator | outcome |
| `PaymentCompleted` | Orchestrator | notification, showcase | terminal success |
| `PaymentFailed` | Orchestrator | notification, showcase | terminal failure |
| `ReversalRequested` / `ReversalSucceeded` | Orchestrator / funds mover | — | compensation |
| `PaymentUncertain` | Orchestrator | **Recovery** | *** triggers self-healing |
| `RecoveryStarted` / `RecoveryAttempted` | Recovery | showcase | observability |
| `RecoveryDecided` | Recovery | **Orchestrator** | *** requests a transition |
| `ReconciliationMismatch` | Recovery | ops, showcase | needs a human |

**Naming rule:** commands are addressed to one consumer (`DebitRequested` = "a
debit was requested of you"); facts are past tense and may have many
consumers. Mixing the two on one topic is what makes event systems rot.
