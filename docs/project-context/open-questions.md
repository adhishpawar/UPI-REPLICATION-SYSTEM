# Open Questions

> Decisions that need **your** input because different answers lead to
> materially different architecture. Nothing here has been assumed.
> Where I have a recommendation, it is stated — but I will not act on the
> blocking ones without your answer.

Status: `BLOCKING` (stops a milestone) · `SOON` (needed within Phase 1) ·
`LATER`.

---

## Q-1 · BLOCKING — Which funding model is the payment core built around first?

The platform has two possible money-holders and the choice changes what gets
built in the next few hours.

- **(a) Bank first.** Build the debit/credit adapters on top of the existing
  `bank-service` ledger. Matches the master plan and the UPI mental model.
  Wallet arrives later as a second `FundsMover`.
- **(b) Wallet first.** Build the wallet ledger and run payments wallet-to-
  wallet. Fewer moving parts; the wallet system currently has zero code, so
  this closes the largest gap.
- **(c) Both.** Full `FundsMover` abstraction with both implementations —
  demonstrates the abstraction, but roughly doubles the vertical slice.

**Recommendation: (a) with the `FundsMover` port in place from day one.** The
bank ledger already exists and works (row locking, ledger, idempotency), so
(a) reuses real code rather than writing new code. Building the *port* now
means (b) is later an additive change, not a refactor.

---

## Q-2 · BLOCKING — Should I start Docker Desktop, or design for Postgres-only?

Kafka is not running and the Docker daemon is down. My plan (D-006) makes the
platform run on **PostgreSQL alone**, with Kafka as a swap-in.

- **(a) Postgres-only now, Kafka later.** Everything runs today. All async
  semantics preserved (at-least-once, idempotent consumers, DLQ) via the
  outbox relay.
- **(b) I start Docker Desktop and use real Kafka.** More authentic, but
  Docker startup on Windows is slow and can fail, and it puts the entire demo
  on a dependency that is currently down.

**Recommendation: (a).** It is also the better teaching artefact — you see
that the guarantee comes from the outbox, not from Kafka. I will not start
Docker Desktop without you saying so.

---

## Q-3 · SOON — Is `MANUAL_REVIEW` acceptable as a terminal state?

The self-healing design targets "95% automatic recovery, zero manual
complaints". Real payment systems keep a manual queue, because some
transactions genuinely cannot be resolved without a human (bank returns
contradictory data, or nothing).

A system that always resolves automatically is a system that guesses when it
does not know — and guessing about money is how money is created. My
recommendation is to keep `MANUAL_REVIEW` and treat "how often do we land
there" as the quality metric. **Confirm you are happy for the demo to
sometimes end in `MANUAL_REVIEW`.**

---

## Q-4 · SOON — RRN format: 12 chars per the doc, or 30 per the code?

Master plan §6.3 says `rrn VARCHAR(12)`, format `YYYYMMDD` + 4-digit
sequence — which is close to the real NPCI RRN. The code uses `VARCHAR(30)`
with `SecureRandom`. A date+sequence RRN needs a sequence generator and is
collision-prone under concurrency; a random RRN is safe but not realistic.

Recommendation: keep the code's random generator, narrow the column to
`VARCHAR(12)`, and document the divergence from the real NPCI format. Cheap,
honest, and unblocks the migration.

---

## Q-5 · SOON — Is `X-User-Id` from a not-yet-existing gateway acceptable for Phase 1?

`paymentOrchestrator` trusts an `X-User-Id` header "set by the API Gateway".
There is no gateway, and `SecurityConfig` is empty — so today any caller can
claim any identity.

Options: (a) validate the `psp-service` JWT directly in the orchestrator using
the JWKS endpoint that already exists; (b) build a minimal gateway; (c) leave
the header trusted and label it loudly as a demo-only shortcut.

**Recommendation: (a).** `psp-service` already publishes
`/.well-known/jwks.json`; wiring `spring-security-oauth2-resource-server` to
it is a small change and makes the auth story real rather than notional.

---

## Q-6 · LATER — Should the wallet hold real KYC fields?

The wallet doc includes `kyc_status`. Storing KYC data — even fake — in a
learning project invites treating it as real. Recommendation: model
`kyc_status` as an enum that gates transaction limits, and store **no** KYC
documents or PII beyond what already exists. Confirm when the wallet is built.

---

## Q-7 · LATER — Do the two git repositories merge?

`25 Self healing UPI` is a separate repository with its own history. Options:
(a) keep separate and consume the platform's events; (b) merge in as a module
preserving history via subtree; (c) port the useful domain classes and archive
the repo.

Recommendation: **(b) subtree merge** when recovery implementation starts —
keeps your history, ends the two-repo drift. No action needed yet.

---

## Q-8 · LATER — Which failure does the showcase demo by default?

The hero scenario. Candidates: credit timeout resolved by reconciliation
(shows `UNCERTAIN` — the most interesting), credit hard-failure resolved by
reversal (shows compensation), or a transient failure resolved by retry
(simplest to read).

Recommendation: make all three selectable, default to the credit timeout,
because it is the one that only a self-healing system can handle correctly.

---

## Answered / assumed with stated reasoning

| # | Question | Working assumption | Why safe |
|---|---|---|---|
| A-1 | Is `user-service` still in scope? | No — superseded (D-004) | `psp-service` strictly supersets it; nothing is deleted |
| A-2 | Is `npci-switch` in scope? | No | It was already deleted on your own `impl/paymentOrchestrator` branch; every class was an empty stub |
| A-3 | PostgreSQL or MySQL for the wallet? | PostgreSQL | Whole platform is Postgres; wallet has no code yet to migrate |
| A-4 | Java 17 or 21? | 21 | Installed JDK is 21; poms say 17. Bumping unlocks virtual threads, which the self-healing design already assumes |
| A-5 | Is regulatory compliance claimed? | **No** | Nothing has been implemented or verified (D-005) |
