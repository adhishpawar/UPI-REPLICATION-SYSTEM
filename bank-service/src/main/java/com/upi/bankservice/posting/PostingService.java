package com.upi.bankservice.posting;

import com.upi.bankservice.Repositories.BankAccountRepository;
import com.upi.bankservice.Repositories.LedgerRepository;
import com.upi.bankservice.models.BankAccount;
import com.upi.bankservice.models.Ledger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The money-holder contract: post money, and answer questions about what was
 * posted.
 *
 * <p>Added alongside the existing {@code BankAccountService} rather than
 * replacing it. That service works, and its {@code SELECT ... FOR UPDATE}
 * approach to balance mutation is correct; what needed fixing was its
 * idempotency model and the absence of a reconciliation query.
 *
 * <h3>Idempotency</h3>
 *
 * Keyed on <b>(transactionId, leg)</b>, enforced by a UNIQUE constraint on the
 * ledger. The previous model keyed on {@code txId} alone and stored the
 * account balance as the "result", which produced two distinct bugs: a debit
 * and a credit sharing a transaction id collided so the second silently
 * returned the first one's result <em>without moving money</em>; and a genuine
 * duplicate call returned a stale balance presented as current.
 *
 * <p>The ledger row <em>is</em> the idempotency record. No second table can
 * drift out of step with it.
 *
 * <h3>Two-phase posting, and the bug that forced it</h3>
 *
 * A posting is written as {@code PENDING} in its own committed transaction
 * <b>before</b> the money moves, then flipped to {@code SUCCESS} or
 * {@code FAILED}.
 *
 * <p>The single-phase version failed in testing, in a way worth recording. A
 * payment's debit request was slow. The orchestrator timed out, correctly
 * recorded the outcome as UNKNOWN, and asked this service what it had
 * recorded. This service was <em>still processing</em> the request and so had
 * written nothing yet. It answered "no posting". Reconciliation reasonably
 * concluded no money had moved and marked the payment DEBIT_FAILED -- and
 * moments later the original request completed and debited the payer.
 * <b>250.00 left the account for a payment recorded as failed.</b>
 *
 * <p>The flaw is a confusion between two very different statements:
 *
 * <pre>
 *   "I have no record of that"        (never received it - safe to fail)
 *   "I have not finished that yet"    (in flight - decide nothing)
 * </pre>
 *
 * <p>A money-holder that cannot distinguish them will eventually be asked
 * during the window and will answer confidently wrong. Writing PENDING on
 * receipt makes the distinction representable, and reconciliation can then
 * wait instead of guessing. It is the same lesson as {@code UNCERTAIN} in the
 * payment state machine, one layer down: the dangerous state is the one the
 * model cannot express.
 *
 * <h3>Concurrency</h3>
 *
 * Balance mutation takes a pessimistic row lock. Optimistic locking would be
 * the wrong instrument here: on a hot account conflicts are expected rather
 * than rare, and the retry cost is high because the work includes a ledger
 * write. Pessimistic is for "conflicts are likely"; optimistic is for
 * "conflicts are unlikely but must not be lost".
 */
@Service
@Slf4j
public class PostingService {

    private final BankAccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final TransactionTemplate newTx;
    private final TransactionTemplate tx;

    public PostingService(BankAccountRepository accountRepository,
                          LedgerRepository ledgerRepository,
                          PlatformTransactionManager txManager) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.tx = new TransactionTemplate(txManager);
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Post a movement. Safe to call repeatedly.
     *
     * @param simulate demo-only failure injection; see {@link FailureSimulator}.
     *                 Null in normal operation.
     */
    public PostingResult post(String txId, Ledger.TransactionType leg,
                              String accountNumber, BigDecimal amount,
                              String rrn, String simulate) {

        // ── Phase 0: idempotency ─────────────────────────────────────────
        Optional<Ledger> existing = tx.execute(s -> ledgerRepository.findByTxIdAndType(txId, leg));
        if (existing != null && existing.isPresent()) {
            Ledger l = existing.get();
            if (l.getStatus() == Ledger.TransactionStatus.PENDING) {
                // Another request for this exact posting is still in flight.
                // Reporting it as "in progress" is the honest answer.
                log.info("Posting already in flight: txId={} leg={}", txId, leg);
                throw new PostingInProgressException(txId, leg.name());
            }
            log.info("Idempotent replay: txId={} leg={} already {} ref={}",
                    txId, leg, l.getStatus(), l.getReference());
            return PostingResult.replay(l);
        }

        // ── Phase 1: record the intent, and COMMIT it ────────────────────
        // Committed on its own so that anyone who asks during the window is
        // told "in flight" rather than "never happened". See the class comment.
        Ledger pending;
        try {
            pending = newTx.execute(s -> ledgerRepository.save(Ledger.builder()
                    .txId(txId).type(leg).accountNumber(accountNumber)
                    .amount(amount).rrn(rrn)
                    .status(Ledger.TransactionStatus.PENDING)
                    .reference(reference(txId, leg))
                    .build()));
        } catch (DataIntegrityViolationException violation) {
            // Do NOT assume this means "duplicate". Any constraint can raise
            // this exception, and treating them all as duplicates is how a
            // schema defect becomes an uncertain payment.
            //
            // That happened here: a stale CHECK constraint rejected every
            // REVERSAL posting, this branch reported it as a duplicate, the
            // caller received 409, mapped it to "outcome unknown", and a
            // payer's compensation sat unresolved. The database was refusing
            // outright and the system read it as ambiguity.
            //
            // Re-reading settles it: if the row is there, someone else won the
            // race and this genuinely is a duplicate. If it is not, the write
            // was rejected for some other reason and must be surfaced as an
            // error, loudly.
            boolean nowExists = Boolean.TRUE.equals(
                    tx.execute(s -> ledgerRepository.findByTxIdAndType(txId, leg).isPresent()));

            if (nowExists) {
                log.warn("Concurrent duplicate posting for txId={} leg={}", txId, leg);
                throw new DuplicatePostingException(txId, leg.name());
            }

            log.error("Posting REJECTED by the database for txId={} leg={} -- "
                    + "not a duplicate, the row does not exist: {}",
                    txId, leg, violation.getMostSpecificCause().getMessage());
            throw violation;
        }

        // ── Phase 2: do the work ─────────────────────────────────────────
        try {
            // Injected before any money moves, so an injected failure is
            // indistinguishable from the real thing to everything upstream.
            FailureSimulator.apply(simulate);

            return tx.execute(s -> {
                BankAccount account = accountRepository
                        .findByAccountNumberForUpdate(accountNumber)
                        .orElseThrow(() -> new AccountNotFoundException(accountNumber));

                boolean isDebit = leg == Ledger.TransactionType.DEBIT;

                if (isDebit && account.getBalance().compareTo(amount) < 0) {
                    throw new InsufficientFundsException(
                            accountNumber, amount, account.getBalance());
                }

                BigDecimal newBalance = isDebit
                        ? account.getBalance().subtract(amount)
                        : account.getBalance().add(amount);

                account.setBalance(newBalance);
                accountRepository.save(account);

                Ledger row = ledgerRepository.findById(pending.getLedgerId()).orElseThrow();
                row.setStatus(Ledger.TransactionStatus.SUCCESS);
                row.setBalanceAfter(newBalance);
                ledgerRepository.save(row);

                log.info("Posted {} {} on {} txId={} balanceAfter={}",
                        leg, amount, accountNumber, txId, newBalance);
                return PostingResult.posted(row);
            });

        } catch (RuntimeException failure) {
            // Mark the posting FAILED so reconciliation gets a definitive "no"
            // rather than finding a PENDING row that never resolves. A refusal
            // that leaves no trace is indistinguishable from a lost request.
            markFailed(pending.getLedgerId(), failure);
            throw failure;
        }
    }

    private void markFailed(java.util.UUID ledgerId, RuntimeException cause) {
        try {
            newTx.executeWithoutResult(s ->
                ledgerRepository.findById(ledgerId).ifPresent(row -> {
                    row.setStatus(Ledger.TransactionStatus.FAILED);
                    ledgerRepository.save(row);
                }));
        } catch (Exception e) {
            // The posting stays PENDING. Correct outcome: reconciliation will
            // keep asking rather than concluding anything, which is what we
            // want when we genuinely do not know.
            log.error("Could not mark posting {} FAILED after {}: {}",
                    ledgerId, cause.getClass().getSimpleName(), e.toString());
        }
    }

    /**
     * What did we record for this transaction and leg?
     *
     * <p>The reconciliation primitive. A read: no side effect, safe to call any
     * number of times. It exists so that a caller whose request timed out can
     * find out what actually happened instead of guessing -- and note that
     * {@code PENDING} is one of the answers it can give.
     */
    public Optional<Ledger> findPosting(String txId, Ledger.TransactionType leg) {
        return tx.execute(s -> ledgerRepository.findByTxIdAndType(txId, leg));
    }

    public List<Ledger> findAllPostings(String txId) {
        return tx.execute(s -> ledgerRepository.findByTxIdOrderByCreatedAtAsc(txId));
    }

    /**
     * Sum of credits minus debits for an account, from the ledger.
     *
     * <p>The stored balance is a materialised convenience; this is the
     * derivable truth. Reconciliation compares the two, and if they ever
     * disagree the ledger wins -- it is append-only in intent, and the balance
     * is not.
     */
    public BigDecimal derivedBalance(String accountNumber) {
        return tx.execute(s -> ledgerRepository.derivedBalance(accountNumber));
    }

    private String reference(String txId, Ledger.TransactionType leg) {
        return "BNK-" + leg.name().charAt(0) + "-" + txId.substring(0, 8).toUpperCase();
    }

    // ── Exceptions ────────────────────────────────────────────────────────

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String acct) {
            super("Account not found: " + acct);
        }
    }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String acct, BigDecimal wanted, BigDecimal have) {
            super("Insufficient funds in " + acct + ": requested " + wanted + ", available " + have);
        }
    }

    public static class DuplicatePostingException extends RuntimeException {
        public DuplicatePostingException(String txId, String leg) {
            super("Duplicate posting for " + txId + "/" + leg);
        }
    }

    /** The same posting is already being processed by another request. */
    public static class PostingInProgressException extends RuntimeException {
        public PostingInProgressException(String txId, String leg) {
            super("Posting in progress for " + txId + "/" + leg);
        }
    }
}
