# Open Questions

> Decisions that need **your** input because different answers lead to
> materially different architecture. Nothing here has been assumed.
>
> **Status as of 2026-08-20:** you chose the recommended option for every
> question. Q-1 through Q-5 and Q-8 are implemented and verified. Q-6 and Q-7
> concern work that has not started, so the recommendation is recorded as the
> decision and will be applied when that work begins — with one caveat on Q-7
> noted below, because its premise changed during the sprint.

Status: `RESOLVED` · `RECORDED` (decided, applies to future work) ·
`BLOCKING` · `SOON` · `LATER`.

---

## Q-1 · RESOLVED — bank first, with the port in place

**Chosen: (a).** `FundsMover` exists with a `BankFundsMover` implementation and
a `FundingSource` enum carrying `WALLET`. Adding the wallet is now one bean and
no change to the saga, state machine, handlers or recovery.

### Original question

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

## Q-2 · RESOLVED — Postgres-only, Kafka as a swap-in

**Chosen: (a).** The transactional outbox runs on PostgreSQL alone.
`KafkaSink` exists and is selected by one config line
(`outbox.relay.sink: kafka`) whenever a broker is available. Docker Desktop was
not started.

### Original question

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

## Q-3 · RESOLVED — `MANUAL_REVIEW` kept

**Chosen: keep it.** Implemented and reachable: recovery escalates there when
the money-holder cannot be reached within the attempt budget, and the state
machine treats it as terminal. The showcase reports it as a correct outcome
rather than an error.

"How often do we land there" is the quality metric; *that we can* is the safety
property. A system that always resolves automatically is guessing.

### Original question

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

## Q-4 · RESOLVED — 12 digits, random suffix

**Chosen: as recommended.** `RrnGenerator` now emits `yyMMdd` + 6 random digits
= 12 digits, matching both the design document and the real NPCI length. The
`SecureRandom` instinct is preserved; the divergence from NPCI's exact scheme is
documented in the class.

### Original question

Master plan §6.3 says `rrn VARCHAR(12)`, format `YYYYMMDD` + 4-digit
sequence — which is close to the real NPCI RRN. The code uses `VARCHAR(30)`
with `SecureRandom`. A date+sequence RRN needs a sequence generator and is
collision-prone under concurrency; a random RRN is safe but not realistic.

Recommendation: keep the code's random generator, narrow the column to
`VARCHAR(12)`, and document the divergence from the real NPCI format. Cheap,
honest, and unblocks the migration.

---

## Q-5 · RESOLVED — identity now comes from a verified token

**Chosen: (a).** The orchestrator validates psp-service's RS256 tokens against
its JWK Set. `X-User-Id` is gone.

Verified: a payment with no token is rejected 401, a forged token 401, spending
from a VPA you do not own 403, and another user's transaction returns 404.

Two limits stated rather than papered over: the observability endpoints remain
open (an operator surface, and a browser's `EventSource` cannot send an
`Authorization` header, so securing the stream would mean a token in a query
string); and tokens are not checked against psp's revocation table, so a
logged-out token stays valid until it expires. Short expiry is the mitigation;
introspection is the fix if revocation ever needs to be immediate.

### Original question

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

## Q-6 · RECORDED — wallet KYC: status enum only, no documents

**Decision (as recommended).** When the wallet is built, `kyc_status` is an enum
that gates transaction limits. **No KYC documents and no PII beyond what already
exists.** Storing realistic-looking KYC data in a learning project invites
treating it as real, and the enum captures everything the limit logic needs.

No code yet — there is no wallet. Recorded so the decision is made before the
schema is, rather than after.

### Original question

The wallet doc includes `kyc_status`. Storing KYC data — even fake — in a
learning project invites treating it as real. Recommendation: model
`kyc_status` as an enum that gates transaction limits, and store **no** KYC
documents or PII beyond what already exists. Confirm when the wallet is built.

---

## Q-7 · RECORDED, with a changed premise — read before acting

**The recommendation was (b) subtree merge "when recovery implementation
starts". Recovery is now implemented — inside `paymentOrchestrator` — which
changes what a merge would achieve.**

A subtree merge today would import `com.upirecovery`'s 14 domain classes as
**dead code**: a second `Transaction`, a second `TransactionState`, and a
parallel state machine that D-001 explicitly rejected. That is the outcome the
unified domain model exists to prevent, so doing it now would work against the
architecture rather than for it.

**What I did instead:** absorbed the design (its `UNCERTAIN`/reconciliation
insight is the backbone of the self-healing subsystem) and left the repository
untouched.

**What I did not do:** merge two git repositories. That restructures your
workspace, is awkward to undo, and the case for it evaporated once recovery
lived elsewhere. Options if you still want the history preserved:

- **(c) port and archive** — the useful classes are already ported in spirit;
  archive the repo with a README pointing here. *My recommendation now.*
- **(b) subtree merge into `reference/self-healing-original/`** — keeps the
  history verbatim and quarantines it from the build. Say the word and I will.

### Original question

`25 Self healing UPI` is a separate repository with its own history. Options:
(a) keep separate and consume the platform's events; (b) merge in as a module
preserving history via subtree; (c) port the useful domain classes and archive
the repo.

Recommendation: **(b) subtree merge** when recovery implementation starts —
keeps your history, ends the two-repo drift. No action needed yet.

---

## Q-8 · RESOLVED — three selectable, credit timeout by default

**Chosen: as recommended.** The showcase offers User Payment, Failure &
Self-Healing (credit timeout, the default) and Compensation (credit refused).
Wallet Payment is shown disabled and labelled "port exists, impl does not"
rather than faked.

### Original question

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
