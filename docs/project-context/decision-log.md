# Decision Log

> Decisions that shape the platform. Each has: context, decision,
> alternatives, trade-offs, consequences. Full ADRs for the weighty ones live
> in `docs/adr/`.
> Status: `PROPOSED` (awaiting your confirmation) · `ACCEPTED` · `SUPERSEDED`.

---

## D-001 · Single writer for transaction state — **PROPOSED**

**Context.** Two systems both model a transaction lifecycle and both claim the
right to write its status: `paymentOrchestrator` (12 states, writes
`transactions.current_state` directly) and the self-healing system (11 states,
whose `architecture.md` invariant #4 says agents publish events and a
persistence consumer performs the write).

**Decision.** The **Payment Orchestrator is the only writer** of transaction
state. Recovery emits a `RecoveryDecision` event; the orchestrator applies it
through the same `TransactionStateMachine` guard as every other transition.

**Alternatives considered.**
- *Recovery owns state after a transaction becomes uncertain.* Rejected:
  handover of ownership mid-lifecycle means the answer to "who decides?"
  depends on the current state — the exact ambiguity that produces
  double-writes.
- *Both write, using optimistic locking to arbitrate.* Rejected: optimistic
  locking prevents lost updates, not contradictory decisions. Two components
  can each write a *valid* state and still disagree about whether money moved.

**Trade-offs.** Recovery becomes advisory, so a recovery action costs one extra
event hop. In exchange, every state change passes exactly one guard, and the
audit trail has one authoritative sequence.

**Consequences.** The self-healing system loses its own `transactions` table
and its parallel state machine; it keeps `recovery_cases`,
`reconciliation_records`, and its classification logic — which is where its
real value was anyway.

---

## D-002 · Modular monolith for Phase 1, not 8 microservices — **PROPOSED**

**Context.** The master plan specifies 8 deployable services. Four of them do
not exist. Kafka and Docker are not running; PostgreSQL is the only working
dependency. The saga currently publishes commands nobody consumes.

**Decision.** Phase 1 is one Spring Boot process with enforced internal module
boundaries, plus the two already-working standalone services (`vpa-service`,
`psp-service`) kept as separate processes.

**Alternatives considered.**
- *Build all 8 services now.* Rejected: four new Spring Boot skeletons consume
  the whole timebox and add no new concept. And the async paths could not be
  executed without Kafka.
- *Single service, no module boundaries.* Rejected: makes later extraction a
  rewrite rather than a move.

**Trade-offs.** Loses genuine process isolation and independent deploys.
Retains every distributed-systems *concept* (async messaging, at-least-once,
idempotent consumers, saga, compensation) because those live in the messaging
substrate, not in the process topology.

**Consequences.** Extraction to V2 is a bean swap plus a module move. If a
module cannot be extracted cleanly later, the boundary was drawn wrong — which
is itself the lesson.

**ADR:** `docs/adr/0002-modular-monolith-first.md`

---

## D-003 · Wallet is a funding source, not a second payment system — **PROPOSED**

**Context.** `Docs/index.html` designs the wallet as a standalone platform with
its own gateway, user service, transaction service, outbox publisher, and
reconciliation service — on MySQL with `BIGINT` ids. The Core UPI platform is
PostgreSQL with UUID ids. Zero wallet code exists, so nothing is being thrown
away.

**Decision.** The wallet becomes a second implementation of the `FundsMover`
port behind the existing payment saga. A payment's funding source is `BANK` or
`WALLET`; everything downstream is unchanged.

**Alternatives considered.**
- *Build the wallet as designed, as a parallel system.* Rejected: duplicates
  the saga, state machine, idempotency, outbox, and recovery — and therefore
  duplicates every correctness bug in both. It also creates a fourth
  `transactions` table.
- *Wallet as a "bank" with a hard-coded IFSC.* Rejected: pretends a
  stored-value account is a bank account; wallet-specific rules (KYC limits,
  top-up, closure) have nowhere to live.

**Trade-offs.** The wallet cannot diverge freely from the payment lifecycle. In
exchange, wallet payments inherit idempotency, reversal, reconciliation and
self-healing for free, and wallet-to-wallet transfer becomes an ordinary
payment where both legs happen to be `WALLET`.

**Consequences.** The wallet doc's MySQL/Feign/Eureka choices are dropped.
Its *good* parts — outbox table shape, `txn_ref` idempotency, distributed-lock
reasoning, reconciliation service — are absorbed into the platform.

---

## D-004 · `user-service` is superseded; `psp-service` owns identity — **PROPOSED**

**Context.** `user-service` (Sep 2025) does thin user CRUD with
`ddl-auto=create`. `psp-service` (Feb 2026) does registration, MPIN, JWT
RS256, JWKS, lockout, and login-attempt auditing against Flyway migrations.
Both model a user. The master plan lists only the PSP.

**Decision.** `psp-service` is the identity authority. `user-service` is
frozen — not deleted — and excluded from the platform's runtime topology.

**Alternatives considered.** *Delete it.* Rejected: it is working code and
carries the KYC-status model the wallet will need. Freezing costs nothing.

**Consequences.** Nothing in the platform may call `user-service`. `userdb` is
left untouched.

---

## D-005 · Learning targets replace the aspirational NFRs — **PROPOSED**

**Context.** The self-healing design states 100,000 TPS sustained, 99.99%
uptime, "RBI-compliant and auditable", and seven Spring AI agents. None is
implemented, and none is verifiable on one laptop with Postgres.

**Decision.** Phase-1 targets are correctness properties, not throughput
numbers: every failure scenario in `docs/testing/failure-scenarios.md` reaches
a deterministic final state with no money created or destroyed. Throughput
targets return in `[PRODUCTION CONCEPT]` documentation only. **No regulatory
compliance is claimed anywhere**, because none has been implemented or
verified.

**Alternatives considered.** *Keep the targets as aspiration.* Rejected: an
unverified NFR in a design document is indistinguishable from a false claim,
and in a payments context that is a habit worth not forming.

**Consequences.** `project-overview.md` for the self-healing repo needs its
goals restated. TimescaleDB, Quartz, and Spring AI are removed from Phase 1
and re-justified individually if and when a real need appears.

---

## D-006 · Transactional outbox with a swappable relay — **PROPOSED**

**Context.** The saga publishes to Kafka inside `@Transactional`, before
commit, and calls it an outbox (G-04). Kafka is not running.

**Decision.** A real `outbox_messages` table written in the same transaction as
the state change, drained by a relay behind an `EventPublisher` port with two
implementations: `InProcessRelay` (Postgres only) and `KafkaRelay`.

**Alternatives considered.**
- *Keep direct Kafka publishing, add retries.* Rejected: retries do not fix
  publish-before-commit; they make a phantom command more likely to land.
- *Two-phase commit across Postgres and Kafka.* Rejected: XA is operationally
  heavy, poorly supported by Kafka, and unnecessary — at-least-once plus
  idempotent consumers is the standard answer.

**Trade-offs.** Polling adds latency (~200 ms) and load. Delivery is
at-least-once, so every consumer must be idempotent — enforced by a
`processed_events` table.

**Consequences.** The platform runs on PostgreSQL alone. Moving to Kafka
changes one bean. If that swap ever requires touching the saga, the port was
drawn wrong.

**ADR:** `docs/adr/0001-transactional-outbox.md`

---

## D-007 · Add `UNCERTAIN` and `RECONCILING` to the state machine — **PROPOSED**

**Context.** A timed-out funds movement currently has no representable state
(G-05).

**Decision.** Add `UNCERTAIN` (outcome unknown, do not act) and `RECONCILING`
(actively determining the truth). Only reconciliation may move a transaction
out of `UNCERTAIN`. Add `MANUAL_REVIEW` as the terminal state for
transactions the system cannot resolve.

**Alternatives considered.** *Treat a timeout as failure and reverse.*
Rejected: it creates money whenever the timeout was a false negative — the
single most expensive bug class in payments.

**Consequences.** `MANUAL_REVIEW` is a legitimate outcome, not a defect. A
system that always self-heals is a system that is guessing.

---

## D-008 · Showcase is vanilla HTML/JS served by `jwebserver` — **PROPOSED**

**Context.** The showcase must be independently runnable, deletable, and free
of backend imports. Node/npm are not installed on this machine.

**Decision.** `backend-showcase/` holds static HTML/CSS/JS with no build step,
served by the JDK 21 `jwebserver`. It consumes the public REST API and an SSE
execution stream.

**Alternatives considered.**
- *React/Vite.* Rejected: no Node, and a build step for one page is cost
  without benefit.
- *Serve from the backend's `static/`.* Rejected: makes the showcase a backend
  deployment artefact, violating "deletable without breaking the backend".

**Trade-offs.** Cross-origin, so the backend must allow the showcase origin —
a normal CORS config, not a coupling. No component framework, so the page is
hand-written DOM.

**Consequences.** `future/frontend` remains a separate branch and is not
started.
