# Documentation Index

## Start here (read in order)

1. [`project-context/project-overview.md`](project-context/project-overview.md) — what this platform is and the rules that bind it
2. [`project-context/system-context.md`](project-context/system-context.md) — what physically exists, verified against the repo
3. [`project-context/domain-context.md`](project-context/domain-context.md) — unified domain model and ownership
4. [`project-context/architecture-context.md`](project-context/architecture-context.md) — how it is built and why
5. [`project-context/decision-log.md`](project-context/decision-log.md) — decisions with alternatives and trade-offs
6. [`project-context/implementation-status.md`](project-context/implementation-status.md) — **update after every change**
7. [`project-context/known-gaps.md`](project-context/known-gaps.md) — defects and missing pieces, with file references
8. [`project-context/open-questions.md`](project-context/open-questions.md) — decisions needing a human
9. [`project-context/learning-roadmap.md`](project-context/learning-roadmap.md) — build order

## Working rule for every session

```
read context  ->  compare against code  ->  change  ->  update context
```

Documentation and implementation must never silently diverge. If this
directory disagrees with the code, **the code is the truth** and the document
is a bug to be fixed immediately.

## Other directories

| Path | Contents |
|---|---|
| `adr/` | Architecture Decision Records |
| `testing/failure-scenarios.md` | Failure/recovery specification and test matrix |
| `architecture/` `api/` `domain/` `database/` `events/` `operations/` `learning/` | filled in as the corresponding milestones land |
