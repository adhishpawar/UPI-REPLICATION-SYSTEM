# Bugs the System Found in Itself

> Eight defects surfaced while building this platform, each only once something
> actually depended on the code in question. They are recorded because the
> *mechanism* of each is more instructive than the fix, and because most would
> have been invisible in a code review.
>
> They fall into two families. **1-5** are models that could not express a
> distinction, found by moving real money through real failures. **6-8** are
> claims nothing had ever checked, found by making something depend on them.

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

## 6. An authentication mechanism that had never once worked

**Found by:** trying to use it.

Turning on real JWT validation meant psp-service finally had to serve its public
key. Four separate defects sat in that path, and none had ever been exercised:

1. **The service could not start from a clean clone.** Its RSA keys are
   gitignored -- correct, a private signing key must never be committed -- but
   nothing could regenerate them. `FileNotFoundException` at bean creation.
2. **Its only test did not compile**, declaring the field as `SecurityConfig`
   instead of `JwtTokenProvider`. And because `spring-boot:run` runs
   test-compile first, that broken test *prevented the service from starting at
   all*.
3. **The JWKS endpoint returned 403.** The security rule permitted
   `/.well-known/jwks.json`; the controller served it under its class-level
   prefix at `/api/v1/auth/.well-known/jwks.json`. The real URL fell through to
   `.anyRequest().authenticated()` -- so the endpoint that exists precisely so
   callers need *no* credentials required credentials.
4. **Then it returned 500.** The handler declared
   `@Autowired RSAPublicKey publicKey` as a **method parameter**. `@Autowired`
   means nothing there; Spring MVC treated it as something to bind from the
   request, found nothing, and failed.

**Why it matters.** Every piece looked right in isolation. There was a JWKS
endpoint, a key config, a token provider, and a test. Read the file listing and
the auth story is complete. Run it and *none* of it worked, because nothing had
ever called it end to end -- the orchestrator was using an `X-User-Id` header
instead.

**Lesson.** Unexercised code is not "working code that isn't used yet"; its
state is simply *unknown*, and the accumulated defects tend to be dense. These
four had co-existed happily for months. One integration test that fetched the
JWK Set and verified a token would have caught all four on day one.

There is a sharper version of this. Gitignoring a private key is correct and was
done correctly -- but "secrets are not in the repository" is only half a secrets
strategy. The other half is a documented, repeatable way to obtain them, and
without it the service was unbootable. A control implemented half-way can be
worse than none, because the half that exists creates the impression the whole
thing does.

---

## 7. A comment that described security the code did not have

**Found by:** reading, then checking.

`VpaMapper` had this, under a column comment reading "Stored AES-256 encrypted":

```java
private String encrypt(String accountNumber) {
    return Base64.getEncoder().encodeToString(accountNumber.getBytes());
}
```

Base64 is an *encoding*. It is publicly reversible, takes no key, and provides
precisely zero confidentiality. Anyone with read access to the table had every
account number.

**Why it matters more than the weakness itself.** The method was named
`encrypt`, the schema said AES-256, and a `// For Production: replace with
AES-256` comment sat directly above the line that did not. Someone auditing this
system would tick "account numbers encrypted at rest" and move on. **A security
control that is documented but absent is more dangerous than one known to be
missing, because nobody goes looking for it.**

**Fix.** Real AES-256-GCM. GCM specifically because it is *authenticated*:
tampering with a stored ciphertext is detected rather than silently decrypting
to some other value. For a field that determines where money goes, integrity
matters as much as confidentiality -- an attacker who can flip bits in an
account number does not need to read it.

Stored values carry a `v1:` prefix so existing Base64 rows remain readable. A
hard cutover would have made every existing VPA unresolvable and every payment
to an existing payee fail: technically a security improvement, operationally an
outage.

**Lesson.** Comments are not tested. Where a comment claims a property, either
verify it or write down that it is aspirational. And when replacing a format in
a live table, version the data before you need to.

---

## 8. A check that could not have worked until identity was real

**Found by:** implementing authentication and noticing what it enabled.

Nothing verified that the payer's VPA belonged to the caller. Any user could
initiate a payment from any VPA they could name.

The interesting part is *why the fix was not available earlier*. The check is
one line:

```java
if (!payer.getUserId().equals(userId)) throw new VpaOwnershipException(...);
```

But while `userId` came from an `X-User-Id` header the caller supplied, that
line compares a **claim** against a **fact** -- an attacker simply sends the
VPA owner's id and the check passes. It would have looked like a security
control and rejected nothing.

**Lesson.** Authorization is downstream of authentication, and not merely in
execution order. Ownership checks written on top of unverified identity are
theatre: they add code, pass review, and protect nothing. Getting authentication
right did not just close one hole -- it made a whole category of check
*possible*.

---

## What findings 6-8 have in common

The later findings have a different shape from the first five. Those were models
that could not express a distinction. These are **claims that were never
checked**:

- a JWKS endpoint that existed but had never been called
- a test that counted as coverage but did not compile
- a method named `encrypt` that encrypted nothing
- an ownership check that could not have worked

Each looked correct in the file listing. Each was false the moment something
actually depended on it. The defence is the same in all four cases: make
something *use* it, end to end, and watch what happens.

---

## What findings 1-5 have in common

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
