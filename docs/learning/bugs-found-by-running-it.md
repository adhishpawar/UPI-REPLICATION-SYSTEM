# Bugs the System Found in Itself

> Five defects surfaced during the first sprint, each only once the platform
> was actually running and moving money. They are recorded here because the
> *mechanism* of each is more instructive than the fix, and because four of the
> five would have been invisible in a code review.

---

## 1. A guard caught the saga skipping a state

**Found by:** the state machine, at runtime, on the first payment.

The saga set `PAYEE_VALIDATED`, published the debit command, and then tried to
record the reply as `PAYEE_VALIDATED -> DEBITED`. That edge does not exist —
`DEBIT_REQUESTED -> DEBITED` does. The guard threw and the payment stopped.

**Why it matters.** The state machine was right and the saga was wrong. A
system that assigns `status` by hand would have accepted the write, and the
recorded state would simply have been a state the payment was never in. The
audit trail would have been quietly false.

**Lesson.** A guard that never rejects anything is not proving anything. This
one earned its place on day one.

---

## 2. `@Transactional` on a `final` method silently did nothing

**Found by:** the execution trace, which showed the same message failing five
times with a `NullPointerException`.

`IdempotentMessageHandler` had `@Autowired` fields and a `final`
`@Transactional handle()` method. Every delivery failed on a null repository.

The chain:

1. `@Transactional` makes Spring wrap the bean in a CGLIB proxy — a generated
   subclass.
2. The proxy instance is allocated **without running a constructor** and
   without field injection. Its own fields are all null. That is normally
   invisible, because intercepted calls are delegated to the real target.
3. CGLIB cannot override a `final` method. So `handle()` was never intercepted,
   ran directly on the proxy, and saw its null fields.

**Why it matters.** Two individually sensible decisions — "make the template
method final so subclasses cannot skip deduplication" and "make it
transactional" — combined into a failure, and the stack trace pointed at a null
field rather than at either decision.

**Lesson.** Proxy-based AOP has sharp edges that the type system does not
guard: `final`, `private`, and self-invocation all silently disable it. Where
the transaction boundary is load-bearing, an explicit `TransactionTemplate`
cannot be defeated this way. That is now the pattern throughout the outbox
relay, the recovery worker and the execution recorder.

---

## 3. "No record of that" is not "not finished yet" — ₹250 destroyed

**Found by:** balances, after a failure-injection run.

The most serious defect of the sprint, and the most interesting.

A debit request was slow. The orchestrator timed out, correctly recorded the
outcome as `UNKNOWN`, and asked the bank what it had recorded. The bank was
**still processing the request** and had written nothing yet, so it answered
"no posting". Reconciliation concluded no money had moved and marked the
payment `DEBIT_FAILED`. Seconds later the original request completed and
debited the payer.

**₹250 left the account for a payment recorded as failed.**

Every individual component behaved correctly. The bank answered truthfully.
The orchestrator did exactly what the design said. The bug was in the
*vocabulary*: the money-holder had no way to say

```
"I have not finished that yet"
```

so it said the only other thing it could:

```
"I have no record of that"
```

**Fix.** Two-phase posting. The bank writes a `PENDING` ledger row in its own
committed transaction *before* the money moves, and flips it to
`SUCCESS`/`FAILED` afterwards. Reconciliation now sees `PENDING` and defers
instead of deciding.

**Lesson.** This is the same lesson as `UNCERTAIN` in the payment state
machine, one layer down. **The dangerous state is the one the model cannot
express** — because the system will not fail to answer, it will answer with
the nearest thing it can represent, and be confidently wrong.

---

## 4. A stale `CHECK` constraint became an "uncertain payment"

**Found by:** a compensation scenario that stranded the payer's money.

`ddl-auto=update` had created `CHECK (type IN ('DEBIT','CREDIT'))` before
`REVERSAL` was added to the enum. Hibernate's update mode only ever **adds**;
it never alters an existing constraint. So the database rejected every
compensating posting.

The rejection then travelled through three layers, getting less accurate at
each one:

```
Postgres:  CHECK constraint violated          (definite refusal)
   ->  PostingService: "must be a duplicate"  (DataIntegrityViolationException)
   ->  HTTP 409                               (already in flight)
   ->  Orchestrator: outcome UNKNOWN          (may or may not have happened)
```

A hard, deterministic schema error was laundered into ambiguity, and a payer's
reversal sat unresolved while the system dutifully re-reconciled it.

**Two fixes.** Drop the stale constraint, and stop assuming
`DataIntegrityViolationException` means "duplicate" — re-read, and if the row
is genuinely absent, surface the real error loudly.

**Lesson.** `ddl-auto=update` is not schema management, and an exception
handler that narrows a category ("some integrity problem") into a specific
cause ("a duplicate") will eventually mislabel something important. `payment_db`
uses Flyway with `ddl-auto: validate` for exactly this reason; migrating
`bank-service` the same way is outstanding.

---

## 5. A test proved the state graph could destroy money

**Found by:** `TransactionStateMachineTest.everyPostDebitStateReachesAResolution`.

Not a runtime failure — a property test written as "once money has left the
payer, every reachable terminal state must account for it". It failed:

```
DEBITED can end in [REVERSED, DEBIT_FAILED, COMPLETED, MANUAL_REVIEW]
```

`DEBIT_FAILED` asserts that no money moved. Reaching it from `DEBITED` means a
payment whose debit was *confirmed* could be recorded as one whose debit never
happened. The path was real:
`DEBITED -> UNCERTAIN -> RECONCILING -> DEBIT_FAILED`.

The root cause was that a single `UNCERTAIN` state could not say **which leg**
was in doubt, so `RECONCILING` had to allow every conclusion any leg might
need.

**Fix.** Split the states by leg: `UNCERTAIN_DEBIT`/`_CREDIT`/`_REVERSAL` and
`RECONCILING_DEBIT`/`_CREDIT`/`_REVERSAL`. Now `RECONCILING_CREDIT` cannot
reach `DEBIT_FAILED` at all, and the invariant holds by construction.

It also deleted code: recovery had been *inferring* which leg was in doubt —
first from which fields happened to be populated (which got reversals wrong),
then from the audit trail. With the leg in the state name, there is nothing to
infer.

**Lesson.** A graph-wide property test protects against states nobody has
thought of yet; an example-based test only protects the paths someone
remembered. And the best fix is not handling a bug but making it
**unrepresentable**.

---

## What these have in common

Four of the five were invisible in the code and only appeared when real money
moved through a real failure. Three of them share one root shape:

> **A model that cannot express a distinction will not report "I don't know".
> It will report the nearest thing it can say, and be believed.**

- The bank could not say "still working" → it said "never happened".
- The state machine could not say "unsure which leg" → it allowed every leg's
  conclusions.
- The exception handler could not distinguish constraint kinds → it called
  everything a duplicate.

Each was fixed by *adding a distinction to the model* rather than adding a
check to the code. That is the difference between a bug that is handled and a
bug that can no longer be written.
