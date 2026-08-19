# UPI Payment Platform

A learning-oriented, enterprise-shaped UPI payment platform: a payment core
that moves money correctly under failure, a self-healing layer that repairs
uncertain transactions, and a developer-facing visualiser that makes the
distributed behaviour observable.

The goal is not a product. The goal is to learn how an engineering team reasons
about a system where correctness is non-negotiable.

---

## What it does today

```
POST /api/v1/payments        Bearer token, verified against psp-service's JWK Set
      |
      v
  Payment Orchestrator ──HTTP──> VPA Service        (who am I paying?)
      |  saga + state machine
      |  transactional outbox
      v
  Outbox Relay ──> Funds Handler ──HTTP──> Bank Service   (move the money)
      |                                       ledger, row locks, idempotent
      v
  money moved, exactly once
```

and when that goes wrong:

```
  credit request times out
      |
      v
  UNCERTAIN_CREDIT          money may or may not have moved.
      |                     NOT retried. NOT reversed.
      v
  RECONCILING_CREDIT        ask the bank what it actually recorded (a read)
      |
      +-- bank has the credit  -> COMPLETED        (reversing would create money)
      +-- bank has no credit   -> REVERSED         (compensate the payer)
      +-- bank unreachable     -> MANUAL_REVIEW    (escalate, do not guess)
```

Verified end to end against PostgreSQL:

| Scenario | Result | Money |
|---|---|---|
| happy path | `COMPLETED` | payer −200.00, payee +200.00 |
| credit times out, bank did credit | `COMPLETED` | payer −150.00, payee +150.00, **no double credit** |
| credit refused | `REVERSED` | payer net **0.00**, three ledger postings |
| duplicate `Idempotency-Key` | same transaction | **no additional money** |

and refuses what it should:

| Attempt | Result |
|---|---|
| payment with no token | `401` |
| payment with a forged token | `401` |
| payment from a VPA you do not own | `403 VPA_NOT_OWNED` |
| reading another user's transaction | `404` (not 403 — a 403 would confirm the id exists) |

---

## Running it

**Requires:** JDK 21, PostgreSQL 17 on `:5432` (`postgres`/`root`).
**Does not require:** Kafka, Docker, or Node.

```bash
createdb -U postgres payment_db          # once
bash scripts/generate-psp-keys.sh        # once — RSA keypair for signing JWTs
```

The keys are gitignored, as a private signing key should be. The script is how
you get them; without it psp-service cannot start.

Four services, each in its own terminal:

```bash
cd vpa-service         && ./mvnw -o -DskipTests spring-boot:run   # :8081
cd psp-service         && ./mvnw -o -DskipTests spring-boot:run   # :8082
cd paymentOrchestrator && ./mvnw    -DskipTests spring-boot:run   # :8083  (first run online)
cd bank-service        && ./mvnw -o -DskipTests spring-boot:run   # :8084
```

Seed two registered users with VPAs and a funded account, then open the showcase:

```bash
bash scripts/seed-demo-data.sh
bash backend-showcase/run.sh             # http://localhost:8090
```

The seed script prints a mobile number, device id and MPIN. Sign in with them in
the showcase, paste the printed VPAs, pick a scenario, press Run.

The sign-in is not decoration: the payment API rejects an unauthenticated call
with 401, and rejects a payment from a VPA you do not own with 403.

---

## Layout

| Path | What it is |
|---|---|
| `paymentOrchestrator/` | the payment core — saga, state machine, outbox, recovery, execution stream |
| `bank-service/` | the money-holder — accounts, ledger, idempotent postings, reconciliation query |
| `vpa-service/` | VPA directory |
| `psp-service/` | identity, MPIN, RS256 tokens, JWK Set |
| `backend-showcase/` | independent visualiser. Static HTML/JS. **Delete it and nothing breaks.** |
| `scripts/` | seed and smoke-test |
| `docs/` | architecture, decisions, gaps, failure spec — **start at `docs/README.md`** |
| `user-service/` | superseded by `psp-service`; frozen, not in the topology |

---

## The three separated concerns

```
                  CORE BACKEND (Spring Boot)
                            |
             REST / SSE contracts, no shared code
                            |
          +-----------------+------------------+
          |                                    |
   BACKEND SHOWCASE                     FUTURE CORE FE
   backend-showcase/                    branch: future/frontend
   built now                            NOT started
   reads the public API only            must not depend on showcase APIs
   deletable                            a separate product
```

The backend imports nothing from either frontend. The showcase imports nothing
from the backend and reads no database — it renders `execution_events` rows the
backend wrote while doing real work, so it cannot show a payment that did not
happen.

---

## Where to read next

1. [`docs/project-context/project-overview.md`](docs/project-context/project-overview.md) — what this is and the rules that bind it
2. [`docs/project-context/architecture-context.md`](docs/project-context/architecture-context.md) — how it is built and why
3. [`docs/testing/failure-scenarios.md`](docs/testing/failure-scenarios.md) — the ten failure modes and how each resolves
4. [`docs/learning/bugs-found-by-running-it.md`](docs/learning/bugs-found-by-running-it.md) — five real defects, and what each taught
5. [`docs/project-context/learning-roadmap.md`](docs/project-context/learning-roadmap.md) — what to build next

---

## The one idea worth taking away

A timeout is not a failure.

When a funds movement does not answer, there are three tempting reactions and
all three can create or destroy money: retry it (double credit), reverse it
(refund a payer who was also paid), or assume it worked (nobody was paid).

The only correct reaction is to ask the money-holder what it actually recorded,
and decide afterwards. Everything else in this platform — the `UNCERTAIN_*`
states, the reconciliation query, the two-phase ledger posting, the outbox —
exists to make that possible.
