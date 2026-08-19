# ADR-0002 — Modular monolith for Phase 1

**Status:** PROPOSED · **Date:** 2026-08-19 · **Decision:** D-002

## Context

The master plan (`Docs/UPI_Implementation_Master_Plan.docx`) specifies eight
deployable services over 18 weeks. Current reality:

- 4 of the 8 exist; 2 of those 4 are genuinely complete
- the Payment Orchestrator cannot start (no configuration at all)
- the bank debit and credit adapters — the components that actually move
  money — do not exist
- Kafka is not running; Docker is not running; PostgreSQL is the only
  available dependency
- the saga therefore publishes commands nobody consumes and awaits replies
  nobody produces

## Decision

Phase 1 is **one Spring Boot process** with enforced internal module
boundaries (`payment`, `bank`, `wallet`, `recovery`, `outbox`, `observability`,
`ports`), plus the two already-working standalone services `vpa-service` and
`psp-service` kept as separate processes.

## Alternatives

- **Build all eight services now.** Rejected: four new Spring Boot skeletons
  consume the entire timebox and add no concept that a module boundary does
  not already teach. The async paths would remain unexecutable without Kafka.
- **One service, no internal boundaries.** Rejected: later extraction becomes
  a rewrite instead of a move.
- **Merge vpa-service and psp-service in too.** Rejected: they work, they are
  independently deployable, and they are the platform's only genuine
  cross-service HTTP interaction — complete with circuit breaker and retry.
  Absorbing them would delete a working demonstration of distributed
  behaviour to satisfy a diagram.

## Trade-offs

**Lost:** true process isolation, independent deployment, independent scaling,
per-service databases, real network partitions between modules.

**Kept:** async messaging semantics, at-least-once delivery, idempotent
consumers, saga orchestration, compensating transactions, timeouts, circuit
breakers, distributed state reconciliation — because these live in the
messaging substrate and the domain model, not in the process topology.

The distinction worth internalising: *microservices are a deployment
strategy, not a design strategy.* Nearly every "distributed systems" concept
in this platform is exercised by the outbox and the saga, not by having eight
JVMs.

## Consequences

- The platform is runnable today, on the infrastructure that is actually up.
- Extraction (ADR-0003, future) is: move a module to its own build, swap
  `InProcessRelay` for `KafkaRelay`, point it at its own schema. If a module
  resists extraction, the boundary was wrong — a cheap and valuable lesson.
- The eight-service target is retained as the V2/V3 shape in
  `architecture-context.md`, not discarded.
