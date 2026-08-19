package com.upi.payment.saga;

import com.upi.payment.domain.entity.Transaction;
import com.upi.payment.domain.entity.TransactionEvent;
import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.domain.event.EventTypes;
import com.upi.payment.domain.event.FundsCommand;
import com.upi.payment.domain.event.FundsOutcome;
import com.upi.payment.exception.TransactionNotFoundException;
import com.upi.payment.exception.VpaNotFoundException;
import com.upi.payment.exception.VpaServiceUnavailableException;
import com.upi.payment.observability.ExecutionRecorder;
import com.upi.payment.ports.EventPublisher;
import com.upi.payment.ports.FundsMovement;
import com.upi.payment.repository.TransactionEventRepository;
import com.upi.payment.repository.TransactionRepository;
import com.upi.payment.statemachine.TransactionStateMachine;
import com.upi.payment.vpa.VpaServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static com.upi.payment.domain.enums.TransactionStatus.*;

/**
 * Drives a payment through its lifecycle.
 *
 * <h3>Saga, not distributed transaction</h3>
 *
 * A payment touches at least two systems that cannot share a transaction: this
 * service and the money-holder. There is no ACID boundary around them. So the
 * payment is a sequence of steps, each locally atomic, each with a defined
 * compensation for the case where a later step fails.
 *
 * <p>Compensation is not rollback. Once a debit is committed at the bank it is
 * visible and cannot be undone; the saga posts a semantically inverse entry
 * beside it. Both remain in the ledger, which is the point -- the audit trail
 * shows what happened, not a tidied version.
 *
 * <h3>What was wrong before, and why the guard caught it</h3>
 *
 * The previous implementation set {@code PAYEE_VALIDATED} and then published
 * the debit command, never entering {@code DEBIT_REQUESTED}. When the reply
 * arrived it attempted {@code PAYEE_VALIDATED -> DEBITED}, which is not a
 * legal edge, and the state machine threw. The happy path could not complete.
 * The same defect existed on the credit leg.
 *
 * <p>Every step below now transitions <em>before</em> emitting the command, in
 * the same transaction, so the recorded state always reflects what has actually
 * been asked for.
 *
 * <h3>The outbox</h3>
 *
 * Commands are appended to the outbox inside the same transaction as the state
 * change. Previously they went straight to {@code KafkaTemplate.send()} before
 * the commit: if the transaction rolled back, a debit was already in flight for
 * a payment that did not exist. See ADR-0001.
 *
 * <h3>Three outcomes, not two</h3>
 *
 * Every funds movement returns SUCCEEDED, FAILED or UNKNOWN. UNKNOWN goes to
 * {@code UNCERTAIN} and is handed to recovery. It is never retried and never
 * compensated on the spot, because both of those actions assume knowledge the
 * system does not have.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestrator {

    private static final String COMPONENT = "payment-orchestrator";

    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository eventRepository;
    private final TransactionStateMachine stateMachine;
    private final EventPublisher events;
    private final VpaServiceClient vpaServiceClient;
    private final ExecutionRecorder recorder;

    @Value("${payment.timeout.debit-seconds:10}")
    private int debitTimeoutSeconds;

    @Value("${payment.timeout.credit-seconds:10}")
    private int creditTimeoutSeconds;

    // ── Step 1: resolve the payee, then ask for the debit ─────────────────

    /**
     * Synchronous on purpose. The user is waiting to be told whose account
     * they are about to pay, and showing them the wrong name is how
     * misdirected payments happen. Everything after this point is
     * asynchronous, because nobody is waiting on it in the same way.
     */
    @Transactional
    public void validatePayee(UUID transactionId) {
        Transaction txn = load(transactionId);
        long start = System.nanoTime();

        try {
            var resolution = vpaServiceClient.resolveVpa(txn.getPayeeVpa());

            recorder.record(txn.getTraceId(), transactionId, "vpa-service",
                    "GET /api/v1/vpa/{address}", ExecutionRecorder.Kind.HTTP_OUT,
                    ExecutionRecorder.Status.OK, ms(start),
                    "resolved to " + resolution.getAccountHolderName(), null);

            applyTransition(txn, PAYEE_VALIDATED, "Payee VPA resolved: "
                    + resolution.getAccountHolderName());
            txn.setPayeeAccountHolderName(resolution.getAccountHolderName());

            requestDebit(txn);

        } catch (VpaNotFoundException ex) {
            recorder.record(txn.getTraceId(), transactionId, "vpa-service",
                    "GET /api/v1/vpa/{address}", ExecutionRecorder.Kind.HTTP_OUT,
                    ExecutionRecorder.Status.FAILED, ms(start), "404 payee not found", null);
            failBeforeMoneyMoved(txn, "PAYEE_VPA_NOT_FOUND: " + txn.getPayeeVpa());

        } catch (VpaServiceUnavailableException ex) {
            recorder.record(txn.getTraceId(), transactionId, "vpa-service",
                    "GET /api/v1/vpa/{address}", ExecutionRecorder.Kind.HTTP_OUT,
                    ExecutionRecorder.Status.FAILED, ms(start), "service unavailable", null);
            // Safe to fail outright: no money has moved, so there is nothing
            // to be uncertain about. Failing before the debit is always free.
            failBeforeMoneyMoved(txn, "VPA_SERVICE_UNAVAILABLE");
        }
    }

    private void requestDebit(Transaction txn) {
        applyTransition(txn, DEBIT_REQUESTED, "Debit requested from payer's bank");
        txn.setStateDeadlineAt(LocalDateTime.now().plusSeconds(debitTimeoutSeconds));
        transactionRepository.save(txn);

        events.publish(EventTypes.AGGREGATE_TRANSACTION, txn.getTransactionId(),
                EventTypes.DEBIT_REQUESTED,
                new FundsCommand(txn.getTransactionId(), txn.getRrn(),
                        FundsMovement.Leg.DEBIT, txn.getPayerAccountNumber(),
                        txn.getAmount(), txn.getCurrency(), txn.getTraceId(),
                        txn.getDemoScenario()),
                txn.getTraceId());

        recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                "outbox.append DebitRequested", ExecutionRecorder.Kind.EVENT_PUB,
                ExecutionRecorder.Status.OK, null,
                "committed with the state change - see ADR-0001", null);
    }

    // ── Step 2: the debit outcome ─────────────────────────────────────────

    @Transactional
    public void onDebitOutcome(FundsOutcome outcome) {
        Transaction txn = load(outcome.transactionId());

        switch (outcome.outcome()) {
            case SUCCEEDED -> {
                applyTransition(txn, DEBITED,
                        "Bank confirmed debit, ref=" + outcome.reference());
                txn.setBankDebitReferenceNumber(outcome.reference());
                requestCredit(txn);
            }
            case FAILED -> {
                // A definite refusal, before any money moved. Terminal and safe.
                applyTransition(txn, DEBIT_FAILED, outcome.failureReason());
                txn.setFailureReason(outcome.failureReason());
                txn.setCompletedAt(LocalDateTime.now());
                txn.setStateDeadlineAt(null);
                transactionRepository.save(txn);
                publishFact(txn, EventTypes.PAYMENT_FAILED);
            }
            case UNKNOWN -> markUncertain(txn,
                    "Debit outcome unknown: " + outcome.failureReason());
        }
    }

    private void requestCredit(Transaction txn) {
        applyTransition(txn, CREDIT_REQUESTED, "Credit requested to payee's bank");
        txn.setStateDeadlineAt(LocalDateTime.now().plusSeconds(creditTimeoutSeconds));
        transactionRepository.save(txn);

        events.publish(EventTypes.AGGREGATE_TRANSACTION, txn.getTransactionId(),
                EventTypes.CREDIT_REQUESTED,
                new FundsCommand(txn.getTransactionId(), txn.getRrn(),
                        FundsMovement.Leg.CREDIT, txn.getPayeeAccountNumber(),
                        txn.getAmount(), txn.getCurrency(), txn.getTraceId(),
                        txn.getDemoScenario()),
                txn.getTraceId());

        recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                "outbox.append CreditRequested", ExecutionRecorder.Kind.EVENT_PUB,
                ExecutionRecorder.Status.OK, null, null, null);
    }

    // ── Step 3: the credit outcome ────────────────────────────────────────

    @Transactional
    public void onCreditOutcome(FundsOutcome outcome) {
        Transaction txn = load(outcome.transactionId());

        switch (outcome.outcome()) {
            case SUCCEEDED -> {
                applyTransition(txn, CREDITED, "Bank confirmed credit, ref="
                        + outcome.reference());
                txn.setBankCreditReferenceNumber(outcome.reference());
                applyTransition(txn, COMPLETED, "Payment completed successfully");
                txn.setCompletedAt(LocalDateTime.now());
                txn.setStateDeadlineAt(null);
                transactionRepository.save(txn);
                publishFact(txn, EventTypes.PAYMENT_COMPLETED);
            }
            case FAILED -> {
                // The bank told us definitively that the credit did not happen,
                // and the debit already committed. Money is with nobody. This
                // is the one case where compensating immediately is correct:
                // the outcome is known, so there is nothing to reconcile.
                applyTransition(txn, CREDIT_FAILED, outcome.failureReason());
                txn.setFailureReason("Credit failed: " + outcome.failureReason());
                transactionRepository.save(txn);
                startReversal(txn, outcome.failureReason());
            }
            case UNKNOWN -> markUncertain(txn,
                    "Credit outcome unknown: " + outcome.failureReason());
        }
    }

    // ── Compensation ──────────────────────────────────────────────────────

    /** Package-visible so recovery can drive a reversal once it has established the facts. */
    @Transactional
    public void startReversal(Transaction txn, String reason) {
        applyTransition(txn, REVERSAL_INITIATED,
                "Compensating: returning funds to payer (" + reason + ")");
        txn.setStateDeadlineAt(LocalDateTime.now().plusSeconds(debitTimeoutSeconds));
        transactionRepository.save(txn);

        events.publish(EventTypes.AGGREGATE_TRANSACTION, txn.getTransactionId(),
                EventTypes.REVERSAL_REQUESTED,
                new FundsCommand(txn.getTransactionId(), txn.getRrn(),
                        FundsMovement.Leg.REVERSAL, txn.getPayerAccountNumber(),
                        txn.getAmount(), txn.getCurrency(), txn.getTraceId(),
                        null),   // never inject a failure into a compensation
                txn.getTraceId());

        recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                "outbox.append ReversalRequested", ExecutionRecorder.Kind.EVENT_PUB,
                ExecutionRecorder.Status.OK, null,
                "compensating transaction - the debit is not undone, an inverse posting is added", null);
    }

    @Transactional
    public void onReversalOutcome(FundsOutcome outcome) {
        Transaction txn = load(outcome.transactionId());

        switch (outcome.outcome()) {
            case SUCCEEDED -> {
                applyTransition(txn, REVERSED, "Debit reversed, ref=" + outcome.reference());
                txn.setCompletedAt(LocalDateTime.now());
                txn.setStateDeadlineAt(null);
                transactionRepository.save(txn);
                publishFact(txn, EventTypes.PAYMENT_REVERSED);
            }
            case FAILED -> {
                // Compensation itself failed. The payer's money is still
                // missing, so this is emphatically not "failed and finished" --
                // it needs a human.
                applyTransition(txn, REVERSAL_FAILED,
                        "Reversal failed: " + outcome.failureReason());
                txn.setFailureReason("REVERSAL_FAILED: " + outcome.failureReason());
                transactionRepository.save(txn);
                log.error("REVERSAL FAILED for txn={} - payer is still out of pocket",
                        txn.getTransactionId());
            }
            case UNKNOWN -> markUncertain(txn,
                    "Reversal outcome unknown: " + outcome.failureReason());
        }
    }

    // ── Uncertainty ───────────────────────────────────────────────────────

    /**
     * Park the payment because we do not know what happened.
     *
     * <p>Everything about this method is a refusal to guess. It does not
     * retry, because the movement may already have happened. It does not
     * compensate, because there may be nothing to compensate. It records that
     * the outcome is unknown and stops, leaving the decision to reconciliation
     * -- which will ask the money-holder rather than assume.
     */
    @Transactional
    public void markUncertain(Transaction txn, String reason) {
        if (txn.getCurrentState().isUncertain()) {
            return;   // already parked
        }
        // The uncertain state names the leg in doubt, so recovery knows which
        // question to ask without having to infer it.
        TransactionStatus uncertain = txn.getCurrentState().uncertainCounterpart();
        if (uncertain == null) {
            log.warn("Cannot mark {} uncertain from state {}",
                    txn.getTransactionId(), txn.getCurrentState());
            return;
        }
        applyTransition(txn, uncertain, reason);
        txn.setUncertainSince(LocalDateTime.now());
        txn.setFailureReason(reason);
        // Deadline cleared: the detector picks these up by state, and leaving
        // a deadline would make it look like a step is still in flight.
        txn.setStateDeadlineAt(null);
        transactionRepository.save(txn);

        recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                "markUncertain", ExecutionRecorder.Kind.STATE,
                ExecutionRecorder.Status.FAILED, null,
                "outcome unknown - NOT retried, NOT reversed. Handing to recovery.",
                Map.of("reason", reason));

        log.warn("Transaction {} is UNCERTAIN: {}", txn.getTransactionId(), reason);
    }


    // ── Applying recovery decisions (D-001) ───────────────────────────────
    //
    // Recovery establishes the facts and decides what should happen. It never
    // writes transaction state itself: every one of the methods below routes
    // through applyTransition, and therefore through the same state machine
    // guard as the ordinary saga path. One writer, one guard, one audit trail.

    /** Move into the matching RECONCILING state: actively establishing the truth. */
    @Transactional
    public void beginReconciling(Transaction txn, String note) {
        TransactionStatus reconciling = txn.getCurrentState().reconcilingCounterpart();
        if (reconciling == null) {
            return;   // not in an uncertain state; nothing to reconcile
        }
        applyTransition(txn, reconciling, note);
        transactionRepository.save(txn);
    }

    /**
     * Reconciliation confirmed the debit did happen. The payment was never
     * broken, only out of date -- pick up where it left off.
     */
    @Transactional
    public void resumeAfterDebit(Transaction txn, String bankReference, String note) {
        applyTransition(txn, DEBITED, note);
        if (bankReference != null) {
            txn.setBankDebitReferenceNumber(bankReference);
        }
        txn.setUncertainSince(null);
        txn.setFailureReason(null);
        requestCredit(txn);
    }

    /** Reconciliation found no debit posting. No money moved, so failing is safe. */
    @Transactional
    public void resolveAsDebitFailed(Transaction txn, String note) {
        applyTransition(txn, DEBIT_FAILED, note);
        txn.setFailureReason(note);
        txn.setCompletedAt(LocalDateTime.now());
        txn.setStateDeadlineAt(null);
        txn.setUncertainSince(null);
        transactionRepository.save(txn);
        publishFact(txn, EventTypes.PAYMENT_FAILED);
    }

    /**
     * Reconciliation confirmed the credit landed; only the response was lost.
     *
     * <p>This is the branch that a naive implementation gets wrong. Treating
     * the original timeout as a failure would have issued a reversal here,
     * refunding a payer who had also been paid.
     */
    @Transactional
    public void resolveAsCompleted(Transaction txn, String bankReference, String note) {
        applyTransition(txn, COMPLETED, note);
        if (bankReference != null) {
            txn.setBankCreditReferenceNumber(bankReference);
        }
        txn.setCompletedAt(LocalDateTime.now());
        txn.setStateDeadlineAt(null);
        txn.setUncertainSince(null);
        txn.setFailureReason(null);
        transactionRepository.save(txn);
        publishFact(txn, EventTypes.PAYMENT_COMPLETED);
    }

    /**
     * Reconciliation confirmed the reversal landed.
     *
     * <p>Two transitions rather than one, because the audit trail should show
     * that a compensation was in progress and then completed -- not that a
     * payment teleported from "unsure" to "refunded".
     */
    @Transactional
    public void resolveAsReversed(Transaction txn, String bankReference, String note) {
        applyTransition(txn, REVERSED, note);
        txn.setCompletedAt(LocalDateTime.now());
        txn.setStateDeadlineAt(null);
        txn.setUncertainSince(null);
        transactionRepository.save(txn);
        publishFact(txn, EventTypes.PAYMENT_REVERSED);
    }

    /**
     * The money-holder could not be reached, so nothing was established.
     *
     * <p>Back to UNCERTAIN, explicitly. Returning to "unsure" is a real
     * outcome; pretending to a conclusion we do not have would not be.
     */
    @Transactional
    public void returnToUncertain(Transaction txn, String note) {
        TransactionStatus back = switch (txn.getCurrentState()) {
            case RECONCILING_DEBIT    -> UNCERTAIN_DEBIT;
            case RECONCILING_CREDIT   -> UNCERTAIN_CREDIT;
            case RECONCILING_REVERSAL -> UNCERTAIN_REVERSAL;
            default -> null;
        };
        if (back == null) {
            return;
        }
        applyTransition(txn, back, note);
        transactionRepository.save(txn);
    }

    /**
     * Escalate to a human.
     *
     * <p>Not a defect. A system that always resolves automatically is a system
     * that guesses when it does not know, and guessing about money is how
     * money gets created. How often payments land here is the quality metric;
     * that they can is the safety property.
     */
    @Transactional
    public void resolveAsManualReview(Transaction txn, String note) {
        applyTransition(txn, MANUAL_REVIEW, note);
        txn.setFailureReason(note);
        txn.setCompletedAt(LocalDateTime.now());
        txn.setStateDeadlineAt(null);
        transactionRepository.save(txn);
        log.error("Transaction {} escalated to MANUAL_REVIEW: {}", txn.getTransactionId(), note);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * The only place a transaction's state is assigned.
     *
     * <p>Guard first, then assign, then append to the audit trail with the
     * <em>correct</em> origin state. The previous {@code failTransaction}
     * assigned the new state and then read {@code getCurrentState()} for the
     * audit row's {@code from}, recording {@code FAILED -> FAILED} and losing
     * where the payment had actually been.
     */
    private void applyTransition(Transaction txn, TransactionStatus to, String description) {
        TransactionStatus from = txn.getCurrentState();
        stateMachine.transition(from, to);
        txn.setCurrentState(to);

        eventRepository.save(TransactionEvent.builder()
                .transactionId(txn.getTransactionId())
                .fromState(from)
                .toState(to)
                .description(description)
                .triggeredBy(COMPONENT)
                .occurredAt(LocalDateTime.now())
                .build());

        recorder.record(txn.getTraceId(), txn.getTransactionId(), COMPONENT,
                from + " -> " + to, ExecutionRecorder.Kind.STATE,
                ExecutionRecorder.Status.OK, null, description, null);

        log.info("txn={} {} -> {} ({})", txn.getTransactionId(), from, to, description);
    }

    /** Terminal failure before any money moved. Always safe. */
    private void failBeforeMoneyMoved(Transaction txn, String reason) {
        applyTransition(txn, FAILED, reason);
        txn.setFailureReason(reason);
        txn.setCompletedAt(LocalDateTime.now());
        txn.setStateDeadlineAt(null);
        transactionRepository.save(txn);
        publishFact(txn, EventTypes.PAYMENT_FAILED);
    }

    private void publishFact(Transaction txn, String eventType) {
        events.publish(EventTypes.AGGREGATE_TRANSACTION, txn.getTransactionId(), eventType,
                Map.of("transactionId", txn.getTransactionId().toString(),
                       "rrn", txn.getRrn(),
                       "state", txn.getCurrentState().name(),
                       "amount", txn.getAmount().toPlainString(),
                       "payerVpa", txn.getPayerVpa(),
                       "payeeVpa", txn.getPayeeVpa(),
                       "failureReason", txn.getFailureReason() == null ? "" : txn.getFailureReason()),
                txn.getTraceId());
    }

    private Transaction load(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    private int ms(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
