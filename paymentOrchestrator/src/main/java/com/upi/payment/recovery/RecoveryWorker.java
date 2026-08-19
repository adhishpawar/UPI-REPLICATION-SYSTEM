package com.upi.payment.recovery;

import com.upi.payment.domain.entity.Transaction;
import com.upi.payment.domain.enums.FundingSource;
import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.exception.TransactionNotFoundException;
import com.upi.payment.observability.ExecutionRecorder;
import com.upi.payment.ports.FundsMovement;
import com.upi.payment.ports.FundsMover;
import com.upi.payment.repository.TransactionRepository;
import com.upi.payment.saga.SagaOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves uncertain payments by establishing what actually happened.
 *
 * <h3>The rule this entire class exists to enforce</h3>
 *
 * <pre>
 *   Reconcile, then decide. Never decide, then act.
 * </pre>
 *
 * When a funds movement's outcome is unknown there are three tempting
 * shortcuts, and every one of them can create or destroy money:
 *
 * <ul>
 *   <li><b>Retry the movement.</b> If the first attempt succeeded, the payee is
 *       paid twice.</li>
 *   <li><b>Assume it failed and reverse.</b> If it succeeded, the payee keeps
 *       the money and the payer is refunded. Money appears from nowhere.</li>
 *   <li><b>Assume it succeeded.</b> If it failed, the payer is out of pocket
 *       and nobody was paid. Money vanishes.</li>
 * </ul>
 *
 * <p>What this class does instead is ask the money-holder what it recorded.
 * That is a <em>read</em>: it has no side effect and is safe to repeat any
 * number of times, which is precisely why it is the only operation permitted
 * while the truth is unknown.
 *
 * <h3>Why recovery does not write transaction state</h3>
 *
 * Decision D-001. Recovery produces a decision; the orchestrator applies it
 * through the same state machine as every other transition. If both wrote
 * state, the system would have two components able to disagree about whether
 * money moved -- and no way to tell which was right.
 *
 * <h3>MANUAL_REVIEW is a correct outcome</h3>
 *
 * When the money-holder cannot be reached after the configured number of
 * attempts, the case is escalated rather than resolved. A system that always
 * resolves automatically is a system that guesses when it does not know, and
 * guessing about money is how money is created.
 */
@Component
@Slf4j
public class RecoveryWorker {

    private static final String COMPONENT = "recovery-worker";
    private static final int BATCH = 10;

    /** How soon to re-ask when the money-holder says a posting is still in flight. */
    private static final int IN_FLIGHT_RECHECK_SECONDS = 2;

    private final RecoveryCaseRepository caseRepository;
    private final ReconciliationRecordRepository reconciliationRepository;
    private final TransactionRepository transactionRepository;
    private final Map<FundingSource, FundsMover> movers;
    private final SagaOrchestrator saga;
    private final ExecutionRecorder recorder;
    private final TransactionTemplate tx;
    private final String workerId;
    private final int maxAttempts;
    private final int leaseSeconds;

    public RecoveryWorker(RecoveryCaseRepository caseRepository,
                          ReconciliationRecordRepository reconciliationRepository,
                          TransactionRepository transactionRepository,
                          List<FundsMover> moverBeans,
                          SagaOrchestrator saga,
                          ExecutionRecorder recorder,
                          TransactionTemplate transactionTemplate,
                          @Value("${recovery.reconcile.max-attempts:5}") int maxAttempts,
                          @Value("${recovery.lease-seconds:30}") int leaseSeconds) {
        this.caseRepository = caseRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.transactionRepository = transactionRepository;
        this.movers = moverBeans.stream()
                .collect(Collectors.toMap(FundsMover::fundingSource, m -> m));
        this.saga = saga;
        this.recorder = recorder;
        this.tx = transactionTemplate;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
        this.workerId = "recovery-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${recovery.detector.interval-ms:2000}")
    public void run() {
        List<RecoveryCase> claimed = tx.execute(s -> claimBatch());
        if (claimed == null) {
            return;
        }
        for (RecoveryCase c : claimed) {
            try {
                tx.executeWithoutResult(s -> reconcile(c));
            } catch (Exception ex) {
                log.error("Recovery case {} failed: {}", c.getCaseId(), ex.toString());
            }
        }
    }

    /**
     * Take a lease on some open cases.
     *
     * <p>The claim is one atomic UPDATE (see
     * {@link RecoveryCaseRepository#claim}). If it matched fewer rows than we
     * selected, another worker got there first, and we simply proceed with
     * what we hold.
     *
     * <p>Transactions are applied with {@link TransactionTemplate} rather than
     * {@code @Transactional} for the same reason as in the outbox relay: a
     * scheduled method calling its own helpers bypasses Spring's proxy, so the
     * annotation would be silently ignored and the work would run without a
     * transaction while looking as though it had one.
     */
    private List<RecoveryCase> claimBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<RecoveryCase> candidates =
                caseRepository.findClaimable(now, PageRequest.of(0, BATCH));
        if (candidates.isEmpty()) {
            return List.of();
        }
        caseRepository.claim(workerId, now.plusSeconds(leaseSeconds), now,
                candidates.stream().map(RecoveryCase::getCaseId).toList());
        return caseRepository.findByClaimedByAndClosedAtIsNull(workerId);
    }

    private void reconcile(RecoveryCase c) {
        Transaction txn = transactionRepository.findById(c.getTransactionId())
                .orElseThrow(() -> new TransactionNotFoundException(c.getTransactionId()));

        // A late reply may have resolved the payment while the case sat in the
        // queue. Nothing to do, and acting anyway would be actively harmful.
        if (!txn.getCurrentState().isUncertain()) {
            closeCase(c, txn.getCurrentState().name(),
                    "Resolved by a late reply before reconciliation ran");
            return;
        }

        if (txn.getCurrentState() == TransactionStatus.UNCERTAIN) {
            saga.beginReconciling(txn, "Asking the money-holder what it actually recorded");
        }

        c.setAttempts(c.getAttempts() + 1);
        FundsMovement.Leg leg = legFor(c.getDetectedState());
        String accountNumber = accountFor(txn, leg);

        recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                "reconcile " + leg, ExecutionRecorder.Kind.RECOVERY,
                ExecutionRecorder.Status.STARTED, null,
                "attempt " + c.getAttempts() + "/" + maxAttempts
                        + " - querying the money-holder (a READ, safe to repeat)", null);

        FundsMovement.LedgerRecord record;
        try {
            record = movers.get(txn.getFundingSource()).query(
                    txn.getTransactionId(), leg, accountNumber);

        } catch (FundsMovement.MoverUnavailableException unavailable) {
            handleUnreachable(c, txn, leg);
            return;
        }

        reconciliationRepository.save(ReconciliationRecord.builder()
                .caseId(c.getCaseId())
                .transactionId(txn.getTransactionId())
                .leg(leg.name())
                .ourBelief("UNKNOWN")
                .theirRecord(record.found() ? record.status() : "NOT_FOUND")
                .matched(false)   // we had no belief to match; this is a discovery
                .rawResponse("{\"found\":" + record.found()
                        + ",\"status\":\"" + record.status() + "\"}")
                .build());

        applyDecision(c, txn, leg, record);
    }

    /**
     * Turn the money-holder's answer into a decision.
     *
     * <p>Each branch is chosen so that the worst case is a payment that needs a
     * human, never a payment that quietly moved the wrong amount of money.
     */
    private void applyDecision(RecoveryCase c, Transaction txn,
                               FundsMovement.Leg leg, FundsMovement.LedgerRecord record) {

        // A posting the money-holder has RECEIVED but not FINISHED is not an
        // answer. "I have no record of that" and "I have not finished that yet"
        // are different statements, and treating the second as the first is
        // how a payment gets marked failed moments before the money leaves.
        // That exact bug occurred during testing; see PostingService's comment.
        if (record.found() && "PENDING".equalsIgnoreCase(record.status())) {
            recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                    "reconcile deferred", ExecutionRecorder.Kind.RECOVERY,
                    ExecutionRecorder.Status.SKIPPED, null,
                    "money-holder reports the " + leg + " is still IN FLIGHT - "
                            + "deciding now would be a guess. Waiting.", null);
            saga.returnToUncertain(txn, "Money-holder still processing the " + leg);
            // Short re-check, not the full lease. "Still working on it" is a
            // transient condition that resolves in seconds; "cannot reach you"
            // is not. Backing off equally for both would leave a payment that
            // was about to resolve sitting idle for the whole lease.
            c.setClaimedUntil(LocalDateTime.now().plusSeconds(IN_FLIGHT_RECHECK_SECONDS));
            c.setResolutionNote("Attempt " + c.getAttempts() + ": posting still PENDING");
            c.setUpdatedAt(LocalDateTime.now());
            caseRepository.save(c);
            return;
        }

        boolean posted = record.found() && "SUCCESS".equalsIgnoreCase(record.status());

        String narrative;
        String outcome;

        switch (leg) {
            case DEBIT -> {
                if (posted) {
                    // The debit did happen. The payment is not broken, only
                    // out of date -- continue where it left off.
                    narrative = "Bank HAS the debit (ref " + record.reference()
                            + "). The money did leave the payer; resuming the credit leg.";
                    saga.resumeAfterDebit(txn, record.reference(), narrative);
                    outcome = "RESUMED";
                } else {
                    // No debit posting. No money moved, so failing is safe and
                    // requires no compensation.
                    narrative = "Bank has NO debit posting. No money moved - safe to fail.";
                    saga.resolveAsDebitFailed(txn, narrative);
                    outcome = "DEBIT_FAILED";
                }
            }
            case CREDIT -> {
                if (posted) {
                    // The credit landed and only the response was lost. The
                    // payment is complete; reversing here would have created
                    // money.
                    narrative = "Bank HAS the credit (ref " + record.reference()
                            + "). The payee was paid - completing. A reversal here"
                            + " would have refunded a payer who was also paid.";
                    saga.resolveAsCompleted(txn, record.reference(), narrative);
                    outcome = "COMPLETED";
                } else {
                    // The credit genuinely never happened, and the debit did.
                    // Now -- and only now -- compensation is correct.
                    narrative = "Bank has NO credit posting, but the payer was debited."
                            + " Compensating with a reversal.";
                    saga.startReversal(txn, "Reconciliation confirmed the credit never landed");
                    outcome = "REVERSING";
                }
            }
            case REVERSAL -> {
                if (posted) {
                    narrative = "Bank HAS the reversal (ref " + record.reference()
                            + "). The payer has been made whole.";
                    saga.resolveAsReversed(txn, record.reference(), narrative);
                    outcome = "REVERSED";
                } else {
                    // The reversal never landed and the payer is still short.
                    // Re-issuing is safe because the posting is idempotent by
                    // (transaction, REVERSAL) at the money-holder.
                    narrative = "Bank has NO reversal posting. Re-issuing"
                            + " (idempotent by transaction and leg, so it cannot double-refund).";
                    saga.startReversal(txn, "Reconciliation found no reversal posting");
                    outcome = "REVERSAL_REISSUED";
                }
            }
            default -> {
                narrative = "Unrecognised leg " + leg;
                outcome = "MANUAL_REVIEW";
            }
        }

        recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                "recovery decision: " + outcome, ExecutionRecorder.Kind.RECOVERY,
                ExecutionRecorder.Status.OK, null, narrative,
                Map.of("leg", leg.name(),
                       "bankHasPosting", record.found(),
                       "bankStatus", String.valueOf(record.status())));

        log.info("Recovery decision for {}: {} - {}", txn.getTransactionId(), outcome, narrative);
        closeCase(c, outcome, narrative);
    }

    /**
     * The money-holder is unreachable, so nothing can be established.
     *
     * <p>The case stays open and is retried with the lease expiring naturally.
     * After the attempt budget is spent it escalates to a human rather than
     * guessing -- because a payment nobody can explain is far better than a
     * payment that was silently resolved the wrong way.
     */
    private void handleUnreachable(RecoveryCase c, Transaction txn, FundsMovement.Leg leg) {
        if (c.getAttempts() >= maxAttempts) {
            String note = "Money-holder unreachable after " + c.getAttempts()
                    + " attempts. Escalating: a human must confirm whether the "
                    + leg + " posting exists.";
            saga.resolveAsManualReview(txn, note);
            closeCase(c, "MANUAL_REVIEW", note);

            recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                    "escalate to MANUAL_REVIEW", ExecutionRecorder.Kind.RECOVERY,
                    ExecutionRecorder.Status.FAILED, null, note, null);
            return;
        }

        // Back to UNCERTAIN and try again later. Explicitly not a guess.
        saga.returnToUncertain(txn, "Money-holder unreachable; will retry reconciliation");
        c.setClaimedUntil(LocalDateTime.now().plusSeconds(leaseSeconds));
        c.setResolutionNote("Attempt " + c.getAttempts() + ": money-holder unreachable");
        c.setUpdatedAt(LocalDateTime.now());
        caseRepository.save(c);

        recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                "reconcile deferred", ExecutionRecorder.Kind.RECOVERY,
                ExecutionRecorder.Status.FAILED, null,
                "could not reach the money-holder - retrying, NOT assuming an outcome", null);
    }

    private void closeCase(RecoveryCase c, String outcome, String note) {
        c.setOutcome(outcome);
        c.setResolutionNote(note != null && note.length() > 1000 ? note.substring(0, 1000) : note);
        c.setClosedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        c.setClaimedUntil(null);
        caseRepository.save(c);
    }

    private FundsMovement.Leg legFor(TransactionStatus detectedState) {
        return switch (detectedState) {
            case DEBIT_REQUESTED -> FundsMovement.Leg.DEBIT;
            case REVERSAL_INITIATED -> FundsMovement.Leg.REVERSAL;
            default -> FundsMovement.Leg.CREDIT;
        };
    }

    private String accountFor(Transaction txn, FundsMovement.Leg leg) {
        // A reversal returns money to the payer, so it posts against the
        // payer's account, not the payee's.
        return leg == FundsMovement.Leg.CREDIT
                ? txn.getPayeeAccountNumber()
                : txn.getPayerAccountNumber();
    }
}
