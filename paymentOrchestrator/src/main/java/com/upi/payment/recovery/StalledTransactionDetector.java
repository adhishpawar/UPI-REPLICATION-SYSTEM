package com.upi.payment.recovery;

import com.upi.payment.domain.entity.Transaction;
import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.observability.ExecutionRecorder;
import com.upi.payment.repository.TransactionEventRepository;
import com.upi.payment.repository.TransactionRepository;
import com.upi.payment.saga.SagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Finds payments that have stopped making progress.
 *
 * <p>The first stage of self-healing, and the one that makes the rest
 * possible. Without it, a payment whose reply never arrives simply sits in
 * {@code CREDIT_REQUESTED} forever: nobody is waiting for it, no error was
 * raised, and no log line says anything is wrong. It is invisible, which is
 * the worst thing a stuck payment can be.
 *
 * <h3>Detection by deadline, not by error</h3>
 *
 * The system cannot rely on being told about this class of failure, because
 * the defining feature of the failure is that nothing was told to anybody. So
 * every in-flight step carries a {@code state_deadline_at}, and passing it
 * without a reply means the outcome is now unknown.
 *
 * <p>Note what the detector does <b>not</b> do. It does not retry, it does not
 * reverse, and it does not decide anything. It moves the payment to
 * {@code UNCERTAIN} and opens a case. Deciding requires facts, and the facts
 * live at the money-holder -- which is the reconciler's job.
 *
 * <h3>Why a sweep rather than a timer per payment</h3>
 *
 * A scheduled timer per in-flight payment lives in memory and dies with the
 * process, taking every pending deadline with it. A sweep over a durable
 * column survives restarts, works across instances, and is trivially
 * observable: the pending set is a query anyone can run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StalledTransactionDetector {

    private static final String COMPONENT = "recovery-detector";

    /** States where a funds movement is in flight and a reply is owed. */
    private static final List<TransactionStatus> AWAITING_REPLY = List.of(
            TransactionStatus.DEBIT_REQUESTED,
            TransactionStatus.CREDIT_REQUESTED,
            TransactionStatus.REVERSAL_INITIATED);

    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository eventRepository;
    private final RecoveryCaseRepository caseRepository;
    private final SagaOrchestrator saga;
    private final ExecutionRecorder recorder;

    @Scheduled(fixedDelayString = "${recovery.detector.interval-ms:2000}")
    @Transactional
    public void sweep() {
        detectStalled();
        openCasesForUncertain();
    }

    /**
     * Stage 1: a reply is overdue, so the outcome is now unknown.
     *
     * <p>"Overdue" is deliberately not "failed". The request may well have
     * been processed and the response lost on the way back, and there is
     * nothing in the timeout itself that distinguishes the two.
     */
    private void detectStalled() {
        List<Transaction> stalled = transactionRepository
                .findByCurrentStateInAndStateDeadlineAtBefore(AWAITING_REPLY, LocalDateTime.now());

        for (Transaction txn : stalled) {
            log.warn("Detected stalled transaction {} in {} (deadline {} passed)",
                    txn.getTransactionId(), txn.getCurrentState(), txn.getStateDeadlineAt());

            recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                    "detect stalled", ExecutionRecorder.Kind.RECOVERY,
                    ExecutionRecorder.Status.FAILED, null,
                    "no reply within the deadline while in " + txn.getCurrentState()
                            + " - outcome is now UNKNOWN, not failed",
                    Map.of("state", txn.getCurrentState().name(),
                           "deadline", String.valueOf(txn.getStateDeadlineAt())));

            saga.markUncertain(txn, "No reply from the money-holder while in "
                    + txn.getCurrentState());
        }
    }

    /**
     * Stage 2: open an investigation for anything sitting in {@code UNCERTAIN}.
     *
     * <p>Separated from stage 1 so that a payment can reach {@code UNCERTAIN}
     * by any route -- a detected timeout, or a funds mover that reported
     * {@code UNKNOWN} directly -- and still be picked up. The case is opened
     * from the state, never from the event that caused it.
     */
    private void openCasesForUncertain() {
        List<Transaction> uncertain = new java.util.ArrayList<>();
        for (TransactionStatus st : List.of(TransactionStatus.UNCERTAIN_DEBIT,
                                            TransactionStatus.UNCERTAIN_CREDIT,
                                            TransactionStatus.UNCERTAIN_REVERSAL)) {
            uncertain.addAll(transactionRepository.findAllByCurrentState(st));
        }

        for (Transaction txn : uncertain) {
            if (caseRepository.existsByTransactionIdAndClosedAtIsNull(txn.getTransactionId())) {
                continue;   // already under investigation
            }

            // detectedState records which leg is in doubt. UNCERTAIN alone
            // does not say whether we are unsure about a debit, a credit or a
            // reversal, and asking the money-holder the wrong question would
            // produce a confidently wrong answer.
            TransactionStatus legInDoubt = inferLegInDoubt(txn);

            // One case per transaction, enforced by a UNIQUE constraint. A
            // payment can become uncertain more than once -- a reversal can
            // time out after a credit already did -- so a previously closed
            // case is REOPENED rather than duplicated. Attempts accumulate
            // across reopenings, which is what lets a payment that keeps
            // failing eventually reach MANUAL_REVIEW instead of looping.
            RecoveryCase c = caseRepository.findByTransactionId(txn.getTransactionId())
                    .map(existing -> {
                        existing.setClosedAt(null);
                        existing.setClaimedBy(null);
                        existing.setClaimedUntil(null);
                        existing.setDetectedState(legInDoubt);
                        existing.setOutcome("IN_PROGRESS");
                        existing.setUpdatedAt(java.time.LocalDateTime.now());
                        return existing;
                    })
                    .orElseGet(() -> RecoveryCase.builder()
                            .transactionId(txn.getTransactionId())
                            .detectedState(legInDoubt)
                            .classification("UNKNOWN_OUTCOME")
                            .strategy("RECONCILE")
                            .outcome("IN_PROGRESS")
                            .build());
            c = caseRepository.save(c);

            log.info("Opened recovery case {} for transaction {} (leg in doubt: {})",
                    c.getCaseId(), txn.getTransactionId(), legInDoubt);

            recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                    "open recovery case", ExecutionRecorder.Kind.RECOVERY,
                    ExecutionRecorder.Status.STARTED, null,
                    "classified as UNKNOWN_OUTCOME, strategy RECONCILE (never blind retry)",
                    Map.of("caseId", c.getCaseId().toString(),
                           "legInDoubt", legInDoubt.name()));
        }
    }

    /**
     * Work out which movement we are unsure about.
     *
     * <p>Derived from the audit trail rather than from the current state,
     * because {@code UNCERTAIN} deliberately erases the distinction. The last
     * request the payment issued is the one still owed an answer.
     */
    /**
     * Which movement is in doubt.
     *
     * <p>Now simply the state, because the uncertain states name their leg.
     * Two earlier versions inferred it -- first from which fields happened to
     * be populated, then from the audit trail -- and the first got reversals
     * wrong, reconciling a timed-out reversal against the credit leg. Encoding
     * the leg in the state removed the inference entirely, which is the better
     * kind of fix: the bug is not handled, it is unrepresentable.
     */
    private TransactionStatus inferLegInDoubt(Transaction txn) {
        return txn.getCurrentState();
    }
}
