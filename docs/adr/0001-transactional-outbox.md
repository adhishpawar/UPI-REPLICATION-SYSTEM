# ADR-0001 — Transactional outbox with a swappable relay

**Status:** PROPOSED · **Date:** 2026-08-19 · **Decision:** D-006

## Context

`SagaOrchestrator` calls `kafkaTemplate.send()` inside `@Transactional`,
before the commit, with a comment describing this as "the outbox pattern".
It is not one. A database commit and a broker publish are two independent
systems and cannot be made atomic without a distributed transaction.

Both possible orderings fail:

- **publish, then commit** (current code): the broker accepts
  `DebitRequested`, the transaction then rolls back. A debit is in flight for
  a payment that does not exist — money moves for nothing.
- **commit, then publish**: state is durable, the process dies before
  publishing, and the payment is stranded forever with no command issued.

Compounding this, `KafkaTemplate.send()` is asynchronous and the failure path
only logs.

Separately: Kafka is not running in this environment and Docker is down. An
architecture that cannot be started cannot be learned from.

## Decision

Write the outbound message into the **same transaction** as the state change:

```java
@Transactional
void step() {
    txn.setCurrentState(DEBIT_REQUESTED);
    transactionRepository.save(txn);
    outbox.append(DebitRequested.of(txn));   // same commit
}
```

A relay drains `outbox_messages WHERE published_at IS NULL` and delivers.
Delivery sits behind an `EventPublisher` port with two implementations:

| Implementation | Sink | Requires |
|---|---|---|
| `InProcessRelay` | in-process consumers | PostgreSQL only |
| `KafkaRelay` | `KafkaTemplate` | Kafka |

Both provide **at-least-once** delivery. Consumers deduplicate on `event_id`
via a `processed_events` table.

## Alternatives

- **Retry the direct Kafka publish.** Rejected: retries do not address
  publish-before-commit; they make a phantom command more likely to land.
- **XA / two-phase commit across Postgres and Kafka.** Rejected: operationally
  heavy, poorly supported by Kafka, and unnecessary — at-least-once plus
  idempotent consumers is the industry-standard answer.
- **Change data capture (Debezium) on the outbox table.** Rejected for
  Phase 1: another running service for no additional concept.
  `[PRODUCTION CONCEPT]` — it is the right answer at scale, because polling
  becomes the bottleneck.

## Trade-offs

- Polling adds ~200 ms of latency and constant low DB load.
- Delivery is at-least-once, so **every consumer must be idempotent**. This is
  a requirement, not a caveat.
- One extra table and one scheduled component.

## Consequences

- The platform runs on PostgreSQL alone — no Kafka, no Docker.
- Moving to Kafka is a bean swap. If that swap ever requires touching the
  saga, the port was drawn wrong; that is the test.
- The guarantee visibly comes from the **outbox**, not from the broker. That
  is the point worth learning: brokers do not give you atomicity with your
  database, and no broker ever will.
