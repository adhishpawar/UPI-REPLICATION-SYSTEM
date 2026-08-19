# Implementation Status

> **Update this file after every meaningful change, before stopping.**
> This is the file that makes sessions resumable. If it disagrees with the
> code, the code wins and this file is wrong — fix it immediately.

**Last reconciled against code:** 2026-08-19
**Branch:** `claude/upi-platform-architecture-b5da56`
**Current phase:** Phase 0 complete — assessment done, implementation not yet
started.

## Legend

`DONE` works and is verified · `PARTIAL` exists, incomplete or unverified ·
`SKELETON` compiles, no behaviour · `NONE` does not exist

## Component status

| Component | Status | Verified how | Notes |
|---|---|---|---|
| vpa-service | `DONE` | code review; `vpa_db` exists | register / resolve / deactivate / list; Flyway V1; `ddl-auto=validate`; partial index on active VPAs |
| psp-service | `DONE` | code review; `pspdb` exists | register, MPIN setup, login, logout, validate, JWKS; RS256; lockout + rate limiter; Flyway V1-V3; one unit test (`JwtTokenProviderTest`) |
| bank-service | `PARTIAL` | code review; `bankdb` exists | accounts, `SELECT..FOR UPDATE` debit/credit, ledger, idempotency table. Defects G-17, G-18 |
| paymentOrchestrator | `PARTIAL` | `mvnw -o compile` = **success**; cannot start | saga, state machine, events, mappers, repos all written. No config (G-09), no migrations (G-11), empty SecurityConfig (G-08), broken happy path (G-01), broken idempotency header (G-02) |
| user-service | `PARTIAL` (frozen) | code review | superseded by psp-service (D-004); `ddl-auto=create` |
| npci-switch | `REMOVED` | — | deleted on `impl/paymentOrchestrator`; every class was an empty stub |
| bank-debit-adapter | `NONE` | — | required to complete any payment |
| bank-credit-adapter | `NONE` | — | required to complete any payment |
| wallet | `NONE` | — | design only (`Docs/index.html`) |
| recovery / self-healing | `SKELETON` | separate repo | 14 domain classes (`Transaction`, `TransactionState`, VOs, policies). No services, no state machine wiring, no Kafka, no persistence |
| outbox + relay | `NONE` | — | claimed in comments, not implemented (G-04) |
| execution-event stream | `NONE` | — | prerequisite for the showcase |
| backend-showcase | `NONE` | — | to be created at `backend-showcase/` |
| API gateway | `NONE` | — | `X-User-Id` contract depends on it (Q-5) |
| notification / QR services | `NONE` | — | master plan phase 5 |
| Integration tests | `NONE` | — | Testcontainers deps declared, no tests written |

## Test inventory

| Test | Kind | Real? |
|---|---|---|
| `UserServiceApplicationTests` | context load | generated stub |
| `VpaServiceApplicationTests` | context load | generated stub |
| `PspServiceApplicationTests` | context load | generated stub |
| `BankServiceApplicationTests` | context load | generated stub |
| `PaymentOrchestratorApplicationTests` | context load | generated stub |
| `JwtTokenProviderTest` | unit | **the only real test in the platform** |

There are zero tests covering money movement, idempotency, state transitions,
concurrency, or failure handling.

## End-to-end capability today

| Flow | Works? |
|---|---|
| Register a user, set MPIN, log in, get a JWT | **yes** |
| Register a VPA and resolve it | **yes** |
| Create a bank account, debit it, credit it (direct REST) | **yes** |
| Initiate a payment | **no** — orchestrator will not start |
| Complete a payment end to end | **no** — no adapters, no Kafka, broken transitions |
| Detect a failed payment | **no** |
| Recover a failed payment | **no** |
| Observe execution | **no** |

## Environment prerequisites for the next session

- PostgreSQL 17 on `localhost:5432`, user `postgres`, password `root` — **up**
- `payment_db` — **must be created**
- Kafka — **down**, not required under D-006
- Docker — **down**, not required under D-006
- Build: `./mvnw -o` works offline from the populated `~/.m2`
