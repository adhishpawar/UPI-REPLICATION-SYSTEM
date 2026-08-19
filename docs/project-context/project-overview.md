# Project Overview — UPI Payment Platform (Learning Umbrella)

> **Status:** Living document. Last reconciled against code: 2026-08-19.
> This file describes *what this platform is*. For *how it is built*, read
> `architecture-context.md`. For *what actually exists today*, read
> `implementation-status.md`.

## One line

A learning-oriented, enterprise-shaped UPI payment platform: a payment core
that moves money correctly under failure, plus a self-healing layer that
detects and repairs uncertain transactions, plus a developer-facing
visualiser that makes the distributed behaviour observable.

## Why this project exists

The goal is **not** a product. The goal is to learn how an enterprise
engineering team reasons about a system where correctness is non-negotiable:

- how a payment request enters the system,
- how money moves and how that movement is recorded,
- how services communicate synchronously vs asynchronously,
- what happens when a step fails *after* money has moved,
- how the system detects that, and heals or reconciles itself,
- how the whole lifecycle is observed.

Optimising for lines of code is explicitly a non-goal.

## The three systems under this umbrella

### System 1 — Core UPI System
The payment-processing platform. Replicates the NPCI UPI topology in
miniature: VPA registry, PSP (auth + user identity), a Payment Orchestrator
that runs the payment saga, and bank-side debit/credit adapters.
**This is the product of the platform. Everything else exists to serve it.**

### System 2 — UPI Digital Wallet
A wallet that participates in the same payment ecosystem: a stored-value
account with its own balance, ledger and top-up path, able to act as the
funding source or destination of a payment.
**Design exists (`Docs/index.html`). No code exists.**

### System 3 — Self-Healing UPI System
A reliability layer around payment processing. Detects transactions that are
stuck in an uncertain state (money left the payer, no confirmation of credit),
classifies the failure, and drives a recovery strategy — retry, compensation,
reconciliation, or escalation — until the transaction reaches a deterministic
final state.
**Design exists in depth (`25 Self healing UPI/context_files/`). Domain
skeleton exists. No behaviour exists.**

## Architecture philosophy (binding)

```
Simple → Correct → Observable → Reliable → Scalable
```

Explicitly *not*:

```
Complex → Distributed → Kubernetes → Many services
```

Rules that follow from this:

1. **No service exists without a reason.** A bounded context earns a process
   boundary only when it needs independent deployment, scaling, or failure
   isolation — not because "enterprise means microservices".
2. **Correctness > availability > performance** for anything touching money.
3. **Every technology must justify itself** in `decision-log.md`. Popularity
   is not a justification.
4. **Two architectures are maintained side by side**: the *Learning
   Architecture* (what gets built and understood now) and the *Production
   Architecture* (what a mature version would evolve toward). Advice is
   always tagged with which one it belongs to.

## Phase policy

- **Phase 1** — payment correctness, distributed-systems mechanics,
  idempotency, transaction state, failure handling, recovery, observability.
- **Phase 2** — AI/ML, only where deterministic rules provably fall short.
  Nothing is added to demonstrate that AI was used.

## Three separate concerns (never merged)

| Concern | Location | Status |
|---|---|---|
| **Core Backend** | service modules at repo root | built now |
| **Backend Showcase** | `backend-showcase/` | built now, independently runnable, deletable |
| **Future Core FE** | separate branch `future/frontend` | **not now** |

The Core Backend must not import, depend on, or know about either frontend.
The showcase must not import backend classes — it talks HTTP/SSE only.

## Money invariants (non-negotiable)

1. Money is never created by accident.
2. Money never disappears.
3. Every transaction reaches a deterministic terminal state.
4. Retries never cause a duplicate debit or credit.
5. Every partial failure is recoverable to a consistent state.

Any change that cannot demonstrate these five properties is rejected,
regardless of how much cleaner it looks.
