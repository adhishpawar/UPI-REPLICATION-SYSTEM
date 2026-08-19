# System Context — Inventory of What Physically Exists

> Reconciled against the repository on 2026-08-19.
> Branch: `claude/upi-platform-architecture-b5da56`
> (= `main` + merged `impl/paymentOrchestrator`).

## Repository map

```
E:\Personal Things\02 Projects\15 Fintech\
│
├── [git repo A — the main platform]
│   ├── vpa-service/          Spring Boot · VPA registry            WORKING
│   ├── psp-service/          Spring Boot · auth/JWT/MPIN           WORKING
│   ├── paymentOrchestrator/  Spring Boot · payment saga            COMPILES, DOES NOT RUN
│   ├── bank-service/         Spring Boot · accounts+ledger         WORKING (legacy shape)
│   ├── user-service/         Spring Boot · user CRUD               LEGACY / SUPERSEDED
│   ├── infra/postgres/init.sql
│   ├── docker-compose.yml    Postgres + Kafka(KRaft) + Kafka UI + Zipkin
│   └── docs/                 ← created by this work
│
├── Docs/                     [untracked design artefacts]
│   ├── UPI_Implementation_Master_Plan.docx   ← System 1 blueprint (18 wks, 8 svcs)
│   ├── VPA_Service_Implementation_Guide.docx
│   ├── PSP_Service_Implementation_Guide.docx
│   ├── index.html                            ← System 2 (WALLET) design
│   └── claude_code_implementation_plan.html  ← System 3 build plan (22 units)
│
└── 25 Self healing UPI/      [git repo B — separate repository]
    ├── context_files/*.md    ← System 3 design (6 files, ~1200 lines)
    ├── src/main/java/com/upirecovery/domain/   14 classes, model only
    ├── docker-compose.yml, monitoring/ (Prometheus + Grafana)
    └── pom.xml
```

**Two independent git repositories.** They do not reference each other.

## Branch topology (repo A)

```
main ────────────────── b972e80  (VPA + PSP + bank + user + npci scaffold)
  └── impl/paymentOrchestrator ── 120e801  (+ orchestrator, − npci-switch)
        └── claude/upi-platform-architecture-b5da56 ── merged, working here
```

`impl/PSP_Service` and `impl/VPA_Service` are merged into `main` already.
`impl/paymentOrchestrator` was **never merged** — the most advanced code in
the platform was sitting on an unmerged branch.

## Service-by-service inventory

| Service | Port | DB | Build | Runs | Real logic |
|---|---|---|---|---|---|
| vpa-service | 8081 | `vpa_db` (exists) | yes | yes | yes — register/resolve/deactivate, Flyway, validation |
| psp-service | 8082 | `pspdb` (exists) | yes | yes | yes — register/MPIN/login/JWT RS256/JWKS, lockout, rate limit |
| paymentOrchestrator | none set | `payment_db` **missing** | yes | **no** | yes — saga, state machine, events; but unwired |
| bank-service | 8081 ⚠ | `bankdb` (exists) | yes | yes | yes — accounts, `SELECT … FOR UPDATE`, ledger, idempotency |
| user-service | 8080 | `userdb` (exists) | yes | yes | thin CRUD, `ddl-auto=create` (wipes on boot) |
| npci-switch | 8083 | `npcidb` | — | — | **deleted on this branch** (was empty scaffold) |
| bank-debit-adapter | 8084 | `debit_db` | — | — | **does not exist** |
| bank-credit-adapter | 8085 | `credit_db` | — | — | **does not exist** |
| notification-service | 8086 | `notif_db` | — | — | **does not exist** |
| qr-service | 8087 | — | — | — | **does not exist** |
| api-gateway | 8080 | — | — | — | **does not exist** |
| wallet-service | — | — | — | — | **does not exist** |
| recovery/self-healing | — | — | compiles | no | domain model only, no services |

⚠ **Port collision**: `vpa-service` and `bank-service` both bind 8081.
`paymentOrchestrator`'s VPA client also defaults to `localhost:8081`.

## Local environment (verified 2026-08-19)

| Dependency | State | Consequence |
|---|---|---|
| JDK | **21.0.1** installed | pom targets Java 17 — fine, but v-threads unavailable by config |
| Maven | no `mvn` on PATH; `mvnw` + populated `~/.m2` | offline builds work (`mvnw -o`) |
| PostgreSQL | **17, running on :5432** (`postgres`/`root`) | databases present: `vpa_db`, `pspdb`, `bankdb`, `userdb`, `npcidb`. **`payment_db` absent.** |
| Kafka | **not running** (:9092 free) | every async path in the design is currently unexecutable |
| Docker daemon | **not running** (CLI 27.4.0 present) | `docker compose up` unavailable right now |
| Redis | not running (:6379 free) | self-healing design assumes Redis |
| Node/npm | **not installed** | showcase must be build-free vanilla JS |
| `jwebserver` | available (JDK 21) | zero-dependency static host for the showcase |

**Key consequence:** the *only* infrastructure available without user action
is PostgreSQL. Any architecture that requires Kafka to demonstrate a payment
cannot be demonstrated today. This directly motivates ADR-0002.

## Config drift observed

- `vpa-service` uses `application.yml` (good: Hikari, `ddl-auto: validate`,
  Flyway, actuator) **and** a stray `application.properties` — two config
  sources for one service.
- `psp-service` uses `ddl-auto=update` *and* Flyway migrations — the two
  fight each other; Hibernate can silently diverge from the migration.
- `user-service` uses `ddl-auto=create` — **drops and recreates all tables on
  every boot.**
- `paymentOrchestrator/application.properties` contains exactly one line
  (`spring.application.name`). No datasource, no Kafka, no WebClient bean.
- `docker-compose.yml` `init.sql` creates 7 databases the local Postgres
  does not have, under user `upi_admin`; services connect as `postgres`/`root`.
  The compose stack and the local stack are two different worlds.
