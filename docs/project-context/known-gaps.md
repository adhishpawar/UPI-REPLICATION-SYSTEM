# Known Gaps

> Every entry cites a file. Severity: **S1** breaks money correctness ·
> **S2** breaks the flow · **S3** quality/maintainability.
> Status: `OPEN` / `FIXED` / `ACCEPTED` (deliberately deferred).

## A. Correctness defects in existing code

### G-01 · S1 · OPEN — The happy path cannot reach `COMPLETED`
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

### G-02 · S1 · OPEN — Idempotency header name is misspelled
`paymentOrchestrator/.../filter/IdempotencyFilter.java`

```java
String idempotencyKey = request.getHeader("Idempotency=Key");  // '=' not '-'
```

The header is always `null`, so **every** payment request is rejected with
`MISSING_IDEMPOTENCY_KEY`. The endpoint is unreachable. (The controller reads
the correctly-spelled `Idempotency-Key`, so the two disagree.)

### G-03 · S1 · OPEN — Column name contains a space
`paymentOrchestrator/.../domain/entity/Transaction.java`

```java
@Column(name = "idempotency key", unique = true, ...)
```

Requires quoted identifiers to exist at all; will not match any hand-written
migration and breaks plain SQL access to the column.

### G-04 · S1 · OPEN — Publish-before-commit masquerading as an outbox
`SagaOrchestrator` (all handlers), `PaymentEventProducer.send()`

`kafkaTemplate.send()` is called inside `@Transactional`, before commit. If the
transaction rolls back, a debit command has already been published for a
payment that does not exist. Send failures are only logged. Full reasoning in
`architecture-context.md` §3.

### G-05 · S1 · OPEN — No `UNCERTAIN` state; timeout is unrepresentable
`domain/enums/TransactionStatus.java`

Twelve states, none of which mean "we do not know". A timed-out credit must be
recorded as success or failure, and either choice creates or destroys money.
See `domain-context.md` §5. This is the gap the self-healing system exists to
fill.

### G-06 · S1 · OPEN — Reversal is not idempotent and has no verification
`SagaOrchestrator.handleCreditFailure()`

Publishes `ReversalRequested` with no guard against the credit having actually
succeeded. If `CreditFailed` was a false negative (bank replied slowly), the
reversal refunds a payer who was also paid.

### G-07 · S2 · OPEN — Non-acked Kafka messages are not redelivered
`kafka/PaymentEventConsumer.java`

The comment states "Do NOT acknowledge — Kafka will redeliver after retry
timeout". With manual-ack mode, swallowing the exception and skipping `ack()`
does **not** redeliver: the container proceeds to the next record and the
offset is simply never committed. The message is effectively lost until a
rebalance. The correct pattern is to let the exception propagate so the
container's error handler retries and routes to the DLT.

### G-08 · S2 · OPEN — `SecurityConfig` is an empty class
`paymentOrchestrator/.../config/SecurityConfig.java` — `public class
SecurityConfig {}` with `spring-boot-starter-security` on the classpath. Every
endpoint gets Spring Security's default HTTP-Basic wall with a generated
password. Identity is taken from an unauthenticated `X-User-Id` header that
nothing sets, so any caller can act as any user.

### G-09 · S2 · OPEN — `paymentOrchestrator` has no runtime configuration
`application.properties` contains one line. No datasource, no JPA, no Flyway,
no Kafka, no server port, no `WebClient` bean (`VpaServiceClient` injects one
that is never defined), no Resilience4j config for the `vpa-service` instances
it references. **The service cannot start.**

### G-10 · S2 · OPEN — Port collision
`vpa-service` and `bank-service` both bind **8081**. The orchestrator's VPA
client also defaults to 8081. Only one can run.

### G-11 · S2 · OPEN — No migrations for `payment_db`
Entities exist; `payment_db` does not exist in the local Postgres and no
Flyway migrations exist for `transactions` / `transaction_events`.

### G-12 · S3 · OPEN — Logging placeholders with no arguments
`PaymentServiceImpl.initiatePayment()`:
`log.info("Initiating payment: payer={} payee={} amount={} userId={}")` — four
placeholders, zero arguments. Logs `{}` literally.

### G-13 · S2 · OPEN — `failTransaction` writes a self-referential audit row
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

### G-17 · S3 · OPEN — `bank-service` `Ledger.txId` is `UNIQUE`
A single transaction has both a debit and a credit posting. A unique
constraint on `tx_id` permits only one, so the ledger physically cannot record
both sides of a transfer within one bank. Must be `UNIQUE (tx_id, type)`.

### G-18 · S1 · OPEN — Bank idempotency record stores a balance, not a response
`bank-service` `Idempotency.result` holds `account.getBalance().toString()`
(despite the field comment "JSON of last response"). On a duplicate call the
service returns *the balance at the time of the original call* as the
"current" balance. Also: the idempotency key is the caller's `txId`, so a
debit and a credit sharing a `txId` collide — the second silently returns the
first one's result **without moving money**.

### G-19 · S2 · OPEN — `vpa-service` has both `application.properties` and
`application.yml`. Two config sources; the properties file wins for
overlapping keys. Only the `.yml` is maintained.

## B. Missing components

| Component | Referenced by | Status |
|---|---|---|
| Bank Debit Adapter (8084) | master plan, orchestrator saga | **absent** |
| Bank Credit Adapter (8085) | master plan, orchestrator saga | **absent** |
| Notification Service (8086) | master plan | absent |
| QR Code Service (8087) | master plan | absent |
| API Gateway (8080) | master plan; `X-User-Id` header contract | absent |
| **Wallet Service** | `Docs/index.html` (full design) | **absent — zero code** |
| Recovery services | `25 Self healing UPI` design | **absent — domain model only** |
| Outbox table + relay | claimed in comments | absent |
| `processed_events` (consumer idempotency) | — | absent |
| Reconciliation job | self-healing design | absent |
| Integration tests | Testcontainers deps declared | **absent — zero tests written** |

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
