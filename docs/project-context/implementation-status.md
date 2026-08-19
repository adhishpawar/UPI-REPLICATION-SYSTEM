# Implementation Status

> **Update this file after every meaningful change, before stopping.**
> This is the file that makes sessions resumable. If it disagrees with the
> code, the code wins and this file is wrong — fix it immediately.

**Last reconciled against code:** 2026-08-19 (end of sprint 1)
**Branch:** `claude/upi-platform-architecture-b5da56`
**Current phase:** M0–M8 complete. A payment moves money end to end, and an
uncertain payment repairs itself.

## Legend

`DONE` works and is verified · `PARTIAL` exists, incomplete or unverified ·
`SKELETON` compiles, no behaviour · `NONE` does not exist

## What changed this sprint

| Milestone | Status | Verified how |
|---|---|---|
| M0 orchestrator starts | `DONE` | boots on :8083, Flyway V1–V5 applied against `payment_db` |
| M1 transactional outbox | `DONE` | every saga step commits state + message together; relay drains it |
| M2 funds movers | `DONE` | real debit/credit against `bank-service` ledger with row locks |
| M3 saga completes | `DONE` | payment reaches `COMPLETED`, balances move exactly once |
| M4 execution stream | `DONE` | ~22 correlated events per payment, persisted + streamed over SSE |
| M5 failure injection + uncertainty | `DONE` | `TIMEOUT_CREDIT`, `REJECT_CREDIT`, `TIMEOUT_DEBIT` reproducible on demand |
| M6 self-healing | `DONE` | credit timeout → `UNCERTAIN_CREDIT` → reconcile → `COMPLETED`, no double credit |
| M7 backend showcase | `DONE` | static page on :8090 driving and rendering real payments |
| M8 money tests | `PARTIAL` | 14 state-machine invariant tests pass; integration tests still shell-based |

## Component status

| Component | Status | Notes |
|---|---|---|
| vpa-service (:8081) | `DONE` | + internal `/account` endpoint returning the decrypted account number |
| psp-service (:8082) | `DONE` | unchanged this sprint |
| paymentOrchestrator (:8083) | `DONE` | **runs.** saga, outbox, funds movers, recovery, execution stream |
| bank-service (:8084) | `DONE` | + two-phase idempotent postings, reconciliation query, failure injection |
| backend-showcase (:8090) | `DONE` | static HTML/JS on `jwebserver`, no build step |
| user-service | frozen | superseded (D-004) |
| wallet | `NONE` | next milestone — a second `FundsMover` |
| API gateway / notification / QR | `NONE` | Phase 2 |

## End-to-end capability today

| Flow | Works? | Evidence |
|---|---|---|
| Register user / MPIN / login / JWT | **yes** | psp-service |
| Register and resolve a VPA | **yes** | vpa-service |
| **Initiate a payment** | **yes** | 202 with transactionId + RRN |
| **Complete a payment end to end** | **yes** | payer −200.00, payee +200.00 |
| **Duplicate request moves no extra money** | **yes** | same txn returned, balances unchanged |
| **Detect a stalled payment** | **yes** | deadline sweep → `UNCERTAIN_*` |
| **Recover an uncertain payment** | **yes** | reconcile → `COMPLETED`, exactly one credit |
| **Compensate a known failure** | **yes** | `REJECT_CREDIT` → `REVERSED`, payer net zero |
| **Escalate what it cannot resolve** | **yes** | `MANUAL_REVIEW` after the attempt budget |
| **Observe the whole thing live** | **yes** | SSE stream + persisted trace |

### Verified run (2026-08-19)

```
happy path        -> COMPLETED   payer 8874.00->8674.00   payee  875.00->1075.00
credit timeout    -> COMPLETED   payer 8674.00->8524.00   payee 1075.00->1225.00
credit rejected   -> REVERSED    payer 8524.00->8524.00   payee 1225.00->1225.00
idempotent replay -> same txn, balances unchanged
```

## Test inventory

| Test | Kind | Count |
|---|---|---|
| `TransactionStateMachineTest` | unit, graph invariants | **14, all passing** |
| `JwtTokenProviderTest` | unit | pre-existing |
| `scripts/smoke-test.sh` | end-to-end shell | happy path, idempotency, recovery, ledger |
| context-load stubs | generated | 5 |

**Still missing:** Testcontainers integration tests, a concurrency test for two
simultaneous payments on one account, and a property test asserting
`sum(debits) == sum(credits)` across all terminal transactions.

## How to run everything

```bash
# 1. PostgreSQL 17 on :5432 (postgres/root) must be running.
#    payment_db is created automatically by Flyway on first boot; create the
#    database itself once:  createdb -U postgres payment_db

# 2. Three backend services, each in its own terminal
cd vpa-service          && ./mvnw -o -DskipTests spring-boot:run    # :8081
cd psp-service          && ./mvnw -o -DskipTests spring-boot:run    # :8082
cd bank-service         && ./mvnw -o -DskipTests spring-boot:run    # :8084
cd paymentOrchestrator  && ./mvnw -o -DskipTests spring-boot:run    # :8083

# 3. Seed a payer and payee
bash scripts/seed-demo-data.sh

# 4. The showcase
bash backend-showcase/run.sh                                        # :8090
```

Note: `paymentOrchestrator` needs one **online** Maven run the first time
(`./mvnw -DskipTests spring-boot:run`) — the local repository is missing
`spring-boot-maven-plugin:3.3.2`. Offline works after that.

## Environment prerequisites

- PostgreSQL 17 on `localhost:5432`, user `postgres`, password `root` — **required**
- `payment_db` — created, migrated to V5
- Kafka / Docker — **not required** (ADR-0001); the outbox relay runs in-process
- Node/npm — **not required**; the showcase uses the JDK's `jwebserver`
