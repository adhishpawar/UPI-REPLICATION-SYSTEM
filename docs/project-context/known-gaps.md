# Known Gaps

> Every entry cites a file. Severity: **S1** breaks money correctness ·
> **S2** breaks the flow · **S3** quality/maintainability.
> Status: `OPEN` / `FIXED` / `ACCEPTED` (deliberately deferred).

## A. Correctness defects in existing code

### G-01 · S1 · FIXED — The happy path cannot reach `COMPLETED`
`paymentOrchestrator/.../saga/SagaOrchestrator.java`

The saga never transitions into `DEBIT_REQUESTED` or `CREDIT_REQUESTED`. It
sets `PAYEE_VALIDATED` and then publishes the debit command. When the reply
arrives, `handleDebitSuccess` calls
`stateMachine.transition(PAYEE_VALIDATED, DEBITED)` — but
`TransactionStateMachine` only permits `DEBIT_REQUESTED -> DEBITED`. The guard
correctly throws `InvalidStateTransitionException`. Identical defect on the
credit leg (`DEBITED -> CREDITED` is not permitted; only
`CREDIT_REQUESTED -> CREDITED` is).

*The state machine is right and the saga is wrong* — which is exactly what a
guard is for. It caught a real bug.

**Fix:** transition to `DEBIT_REQUESTED` / `CREDIT_REQUESTED` in the same
transaction that appends the outbox command.

### G-02 · S1 · FIXED — Idempotency header name is misspelled
`paymentOrchestrator/.../filter/IdempotencyFilter.java`

```java
String idempotencyKey = request.getHeader("Idempotency=Key");  // '=' not '-'
```

The header is always `null`, so **every** payment request is rejected with
`MISSING_IDEMPOTENCY_KEY`. The endpoint is unreachable. (The controller reads
the correctly-spelled `Idempotency-Key`, so the two disagree.)

### G-03 · S1 · FIXED — Column name contains a space
`paymentOrchestrator/.../domain/entity/Transaction.java`

```java
@Column(name = "idempotency key", unique = true, ...)
```

Requires quoted identifiers to exist at all; will not match any hand-written
migration and breaks plain SQL access to the column.

### G-04 · S1 · FIXED — Publish-before-commit masquerading as an outbox
`SagaOrchestrator` (all handlers), `PaymentEventProducer.send()`

`kafkaTemplate.send()` is called inside `@Transactional`, before commit. If the
transaction rolls back, a debit command has already been published for a
payment that does not exist. Send failures are only logged. Full reasoning in
`architecture-context.md` §3.

### G-05 · S1 · FIXED — No `UNCERTAIN` state; timeout is unrepresentable
`domain/enums/TransactionStatus.java`

Twelve states, none of which mean "we do not know". A timed-out credit must be
recorded as success or failure, and either choice creates or destroys money.
See `domain-context.md` §5. This is the gap the self-healing system exists to
fill.

### G-06 · S1 · PARTLY FIXED — Reversal verification
`SagaOrchestrator.onCreditOutcome()`

Two of the three concerns are now addressed:

- **Idempotent** — the reversal is keyed `(transactionId, REVERSAL)` at the
  money-holder, so a repeat posts nothing.
- **False negatives narrowed** — `CREDIT_FAILED` is now reached only from a
  definitive 4xx business refusal. Timeouts, 5xx and 409 all map to
  `UNCERTAIN_CREDIT` and are reconciled instead.

**Still open:** compensation on a *definite* refusal does not re-verify with the
money-holder before reversing. It trusts the 422. That is defensible — a 422 is
the money-holder stating it did not post — but a paranoid implementation would
query first. Worth doing if the bank contract is ever less trustworthy than
this one.

### G-07 · S2 · FIXED — Non-acked Kafka messages are not redelivered
`kafka/PaymentEventConsumer.java`

The comment states "Do NOT acknowledge — Kafka will redeliver after retry
timeout". With manual-ack mode, swallowing the exception and skipping `ack()`
does **not** redeliver: the container proceeds to the next record and the
offset is simply never committed. The message is effectively lost until a
rebalance. The correct pattern is to let the exception propagate so the
container's error handler retries and routes to the DLT.

### G-08 · S1 · FIXED — `SecurityConfig` was empty, and identity was a header
`public class SecurityConfig {}` with `spring-boot-starter-security` on the
classpath meant Spring Boot's default applied: every endpoint behind HTTP Basic
with a generated password. Worse, identity came from an unauthenticated
`X-User-Id` header "set by the API Gateway" - and no gateway existed, so **any
caller could move any user's money**.

Now an OAuth2 resource server validating psp-service's RS256 tokens against its
published JWK Set. No shared secret (this service holds only the public half),
no call to psp on the request path, and psp can rotate its signing key without
redeploying anything here.

### G-09 · S2 · FIXED — `paymentOrchestrator` has no runtime configuration
`application.properties` contains one line. No datasource, no JPA, no Flyway,
no Kafka, no server port, no `WebClient` bean (`VpaServiceClient` injects one
that is never defined), no Resilience4j config for the `vpa-service` instances
it references. **The service cannot start.**

### G-10 · S2 · FIXED — Port collision
`vpa-service` and `bank-service` both bind **8081**. The orchestrator's VPA
client also defaults to 8081. Only one can run.

### G-11 · S2 · FIXED — No migrations for `payment_db`
Entities exist; `payment_db` does not exist in the local Postgres and no
Flyway migrations exist for `transactions` / `transaction_events`.

### G-12 · S3 · FIXED — Logging placeholders with no arguments
`PaymentServiceImpl.initiatePayment()`:
`log.info("Initiating payment: payer={} payee={} amount={} userId={}")` — four
placeholders, zero arguments. Logs `{}` literally.

### G-13 · S2 · FIXED — `failTransaction` writes a self-referential audit row
`SagaOrchestrator.failTransaction()` sets `currentState = FAILED` and *then*
calls `appendEvent(txn, txn.getCurrentState(), FAILED, ...)`, recording
`FAILED -> FAILED`. The origin state is lost from the audit trail.

### G-14 · S3 · OPEN — Field injection in `@RequiredArgsConstructor` classes
`VpaServiceImpl` and `BankAccountService` declare `@RequiredArgsConstructor`
*and* `@Autowired` on non-final fields. The generated constructor takes no
arguments; injection silently falls back to reflection. Harmless at runtime,
but it defeats the immutability the annotation was chosen for and makes the
classes hard to unit-test without a Spring context.

### G-15 · S2 · ACCEPTED — `user-service` runs `ddl-auto=create`
Drops and recreates every table on each boot. Acceptable only because the
service is superseded by `psp-service`; see D-004.

### G-16 · S3 · OPEN — `psp-service` runs Flyway **and** `ddl-auto=update`
Two schema authorities. Hibernate can silently add columns the migrations do
not know about, so a fresh deploy differs from a long-lived dev database.

### G-17 · S3 · FIXED — `bank-service` `Ledger.txId` is `UNIQUE`
A single transaction has both a debit and a credit posting. A unique
constraint on `tx_id` permits only one, so the ledger physically cannot record
both sides of a transfer within one bank. Must be `UNIQUE (tx_id, type)`.

### G-18 · S1 · FIXED — Bank idempotency record stores a balance, not a response
`bank-service` `Idempotency.result` holds `account.getBalance().toString()`
(despite the field comment "JSON of last response"). On a duplicate call the
service returns *the balance at the time of the original call* as the
"current" balance. Also: the idempotency key is the caller's `txId`, so a
debit and a credit sharing a `txId` collide — the second silently returns the
first one's result **without moving money**.

### G-19 · S2 · OPEN — `vpa-service` has both `application.properties` and
`application.yml`. Two config sources; the properties file wins for
overlapping keys. Only the `.yml` is maintained.

### G-20 · S1 · FIXED — Money-holder could not say "still processing"
`bank-service` wrote a ledger row only after the money moved, so a
reconciliation query arriving mid-request was told "no posting". A payment was
marked `DEBIT_FAILED` moments before the debit landed: **250.00 destroyed.**
Fixed by two-phase posting (`PENDING` committed on receipt). Full account in
`docs/learning/bugs-found-by-running-it.md`.

### G-21 · S1 · FIXED — Stale `CHECK` constraint mislabelled as a duplicate
`ddl-auto=update` left `CHECK (type IN ('DEBIT','CREDIT'))` in place after
`REVERSAL` was added, so every compensating posting was rejected. The rejection
was caught as `DataIntegrityViolationException`, reported as "duplicate",
returned as 409, and read by the orchestrator as "outcome unknown" — turning a
hard schema error into a stranded payment. Fixed in `data.sql`, plus a narrower
exception handler.

### G-22 · S1 · FIXED — `@Transactional` on a `final` method was a no-op
CGLIB cannot override a `final` method, so the proxy's own (null) fields were
used instead of the target's. Every message delivery failed with an NPE. Now
uses an explicit `TransactionTemplate`, as do the outbox relay, the recovery
worker and the execution recorder.

### G-23 · S1 · FIXED — Single `UNCERTAIN` state let a confirmed debit end as `DEBIT_FAILED`
Surfaced by a graph-wide property test. Uncertain and reconciling states are
now leg-specific, which also removed the leg-inference logic entirely.

### G-24 · S2 · FIXED — Cross-service identifier formats disagreed
`bank-service` issued account numbers like `ANN0838039073` and IFSC codes like
`AXIS1835124`; `vpa-service` correctly validates `^[0-9]{9,18}$` and
`^[A-Z]{4}0[A-Z0-9]{6}$` and rejected every account the bank created. Only
visible once the two were wired together.

### G-25 · S2 · FIXED — VPA account numbers were returned still encoded
`VpaMapper` Base64-encodes the account number on write; the new internal
resolution endpoint returned the stored value. The orchestrator then asked the
bank to debit `NTUxMzE5ODkzNTM1`.

**Follow-up, now also fixed.** That method was Base64 - **encoding, not
encryption** - while the column comment claimed "Stored AES-256 encrypted". A
security control that is documented but absent is more dangerous than one known
to be missing, because nobody goes looking. Replaced with real AES-256-GCM in
`AccountNumberCipher`.

GCM specifically, because it is authenticated: tampering with a stored
ciphertext is detected rather than silently decrypting to some other account
number. For a field that determines where money goes, integrity matters as much
as confidentiality.

Stored values carry a `v1:` prefix so the previous Base64 rows remain readable -
a hard cutover would have made every existing VPA unresolvable and every payment
to an existing payee fail. Both paths verified working.

**Still open:** the key lives in configuration with a committed development
default. That is not key management; production needs a KMS, rotation, and a key
id in the ciphertext. The version prefix is the hook that makes that possible.

### G-26 · S3 · OPEN — `bank-service` still uses `ddl-auto=update`
The cause of G-21. Schema drift is currently repaired by hand in `data.sql`.
Should move to Flyway with `ddl-auto: validate`, as `payment_db` already does.

### G-27 · S2 · OPEN — Transaction still conflates intent and attempt
Acceptable while recovery only resumes or compensates. Must be split into
Payment + PaymentAttempt before recovery is allowed to re-attempt a leg, or
"which attempt failed?" has no answer.

### G-28 · S3 · OPEN — `paymentOrchestrator` cannot build fully offline
`spring-boot-maven-plugin:3.3.2` and `maven-surefire-plugin:3.2.5` are absent
from the local repository, so the first `spring-boot:run` and any `test` run
need network access. Aligning the parent to 3.5.x (as the other services use)
would resolve it.

### G-29 - S2 - FIXED - psp-service could not be started from a clean clone
The RSA signing keys it reads from the classpath are gitignored (correctly - a
private key must never be committed) but there was no way to regenerate them,
so the service failed at bean creation with a FileNotFoundException.
"Secrets are not in the repository" is only half a secrets strategy; the other
half is a documented, repeatable way to obtain them. Added
`scripts/generate-psp-keys.sh`.

### G-30 - S2 - FIXED - The platform's only real test did not compile
`JwtTokenProviderTest` declared its field as `SecurityConfig`, which has none of
the methods it calls. Because `spring-boot:run` runs test-compile first, this
also **prevented psp-service from starting at all**. A test that does not
compile is worse than no test: it looks like coverage in a file listing and
provides none. Now compiles; 5 tests pass.

Its `generateExpiredToken` call was satisfied by minting the expired token in
the test rather than adding that method to `JwtTokenProvider` - a security
component should not carry a method whose only purpose is producing invalid
credentials.

### G-31 - S1 - FIXED - The JWKS endpoint had never worked
Two independent defects on the one endpoint whose entire purpose is letting
other services verify tokens without credentials:

1. `SecurityConfig` permitted `/.well-known/jwks.json` while the controller
   served it under its class-level prefix at
   `/api/v1/auth/.well-known/jwks.json`. The real URL fell through to
   `.anyRequest().authenticated()` and returned **403**.
2. The handler declared `@Autowired RSAPublicKey publicKey` as a **method
   parameter**. `@Autowired` has no meaning there; Spring MVC tried to bind it
   from the request, found nothing, and returned **500**.

The key is now a constructor dependency and the real path is permitted.

### G-32 - S3 - FIXED - psp-service pulled in Redis and used none of it
`spring-boot-starter-data-redis` was declared but no code referenced it. Spring
Boot still auto-configured a connection factory and health indicator, which
failed against a Redis that is not running: a stack trace every few seconds and
a DOWN health status for a dependency the service does not have. Removed.

### G-33 - S1 - FIXED - Any authenticated user could spend from any VPA
Nothing verified that the payer VPA belonged to the caller. The check was
impossible while identity arrived as a caller-supplied header - it would have
compared a claim against a fact and rejected nothing. With a verified token the
comparison means something, and it is now enforced (403 `VPA_NOT_OWNED`).

## B. Missing components

| Component | Referenced by | Status |
|---|---|---|
| Bank debit/credit execution | master plan, orchestrator saga | **built** — as `FundsCommandHandler` + `BankFundsMover` in-process (D-002), not as separate services |
| Notification Service (8086) | master plan | absent |
| QR Code Service (8087) | master plan | absent |
| API Gateway (8080) | master plan; `X-User-Id` header contract | absent |
| **Wallet Service** | `Docs/index.html` (full design) | **absent — zero code** |
| Recovery services | `25 Self healing UPI` design | **absent — domain model only** |
| Outbox table + relay | ADR-0001 | **built** |
| `processed_events` (consumer idempotency) | — | **built** |
| Reconciliation | self-healing design | **built** — detector, worker, reconciler |
| Execution trace + SSE | showcase + operability | **built** |
| Integration tests | Testcontainers deps declared | still absent; shell smoke test covers the flows |
| Concurrency test (two payments, one account) | — | **absent** |
| Property test: `sum(debits) == sum(credits)` | — | **absent** |

## C. Documentation vs. code conflicts

| # | Documentation says | Code does | Resolution |
|---|---|---|---|
| C-1 | Master plan: orchestrator on **8083** | no port configured; 8083 was `npci-switch` | orchestrator takes 8083 |
| C-2 | Master plan: `rrn VARCHAR(12)`, `YYYYMMDD`+4-digit sequence | `VARCHAR(30)`, SecureRandom | **UNKNOWN — see open-questions Q-4** |
| C-3 | Master plan: `POST /payments` request carries `idempotencyKey` in the **body** | code reads it from the **header** | header is correct (REST convention); update the doc |
| C-4 | Master plan: 409 on duplicate returns the original response | code returns 200 from the filter, 202 from the service | standardise on 200 + original body |
| C-5 | Self-healing: agents publish to Kafka, a consumer writes the DB | orchestrator writes the DB directly | **conflict — resolved by D-001** |
| C-6 | Self-healing: 100K TPS, 99.99% uptime, Spring AI agents, TimescaleDB, Quartz | nothing implemented | **unrealistic for the learning phase — see D-005** |
| C-7 | Wallet doc: **MySQL**, `BIGINT AUTO_INCREMENT` ids, Eureka/Consul, Feign | platform is **PostgreSQL**, UUID ids, WebClient | align wallet to the platform; see D-003 |
| C-8 | Wallet doc: separate Transaction Service + Outbox Publisher + Reconciliation Service | — | fold into the platform's existing saga/outbox/recovery |
| C-9 | `docker-compose.yml` creates 7 DBs as `upi_admin` | services connect as `postgres`/`root` to a *local* Postgres | two disconnected environments; pick one |
