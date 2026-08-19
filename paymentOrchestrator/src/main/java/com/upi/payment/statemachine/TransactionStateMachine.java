package com.upi.payment.statemachine;

import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.exception.InvalidStateTransitionException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.upi.payment.domain.enums.TransactionStatus.*;

/**
 * The single authority on what a payment is allowed to do next.
 *
 * <p>Structure: {@code EnumMap<State, EnumSet<AllowedNext>>}. {@code EnumMap}
 * is array-backed and indexed by ordinal; {@code EnumSet} is a bitmask in a
 * single long. Both are O(1) with no hashing and no allocation on lookup.
 *
 * <p><b>This guard has already earned its keep.</b> The saga used to publish a
 * debit command while leaving the transaction in {@code PAYEE_VALIDATED}; when
 * the reply arrived it attempted {@code PAYEE_VALIDATED -> DEBITED}, which is
 * not a legal transition, and this class threw. The state machine was right
 * and the saga was wrong. That is exactly what a guard is for, and it is why
 * state must never be assigned by hand anywhere else in the codebase.
 *
 * <p><b>Ownership (D-001).</b> This class is reached only from the Payment
 * Orchestrator. Recovery never transitions a transaction directly; it emits a
 * {@code RecoveryDecision} which the orchestrator then applies through here.
 * Two writers of transaction state means no source of truth for whether money
 * moved.
 */
@Component
public class TransactionStateMachine {

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED;

    static {
        ALLOWED = new EnumMap<>(TransactionStatus.class);

        // ── Pre-money. Failure here is free: nothing has moved. ───────────
        ALLOWED.put(INITIATED,          EnumSet.of(PAYEE_VALIDATED, FAILED));
        ALLOWED.put(PAYEE_VALIDATED,    EnumSet.of(DEBIT_REQUESTED, FAILED));

        // ── Debit leg. Three outcomes, not two: yes, no, and unknown. ─────
        ALLOWED.put(DEBIT_REQUESTED,    EnumSet.of(DEBITED, DEBIT_FAILED, UNCERTAIN));

        // ── Credit leg. Same three outcomes. ──────────────────────────────
        ALLOWED.put(DEBITED,            EnumSet.of(CREDIT_REQUESTED, UNCERTAIN));
        ALLOWED.put(CREDIT_REQUESTED,   EnumSet.of(CREDITED, CREDIT_FAILED, UNCERTAIN));
        ALLOWED.put(CREDITED,           EnumSet.of(COMPLETED));

        // ── Compensation. A known credit failure after a committed debit. ─
        ALLOWED.put(CREDIT_FAILED,      EnumSet.of(REVERSAL_INITIATED));
        ALLOWED.put(REVERSAL_INITIATED, EnumSet.of(REVERSED, REVERSAL_FAILED, UNCERTAIN));
        // Compensation failing is not "failed" -- money is still missing.
        ALLOWED.put(REVERSAL_FAILED,    EnumSet.of(MANUAL_REVIEW, REVERSAL_INITIATED));

        // ── Uncertainty. The only way OUT of UNCERTAIN is through
        //    reconciliation. There is deliberately no UNCERTAIN -> COMPLETED
        //    and no UNCERTAIN -> REVERSED edge: nothing may decide the
        //    outcome of a payment without first establishing the facts.
        ALLOWED.put(UNCERTAIN,          EnumSet.of(RECONCILING));
        ALLOWED.put(RECONCILING,        EnumSet.of(
                COMPLETED,           // the money-holder confirms the credit landed
                REVERSAL_INITIATED,  // it confirms the credit never landed
                DEBIT_FAILED,        // it confirms the debit never landed
                DEBITED,             // debit landed; carry on with the credit leg
                UNCERTAIN,           // could not reach it; try again later
                MANUAL_REVIEW));     // out of attempts, or contradictory answers

        // ── Terminal ──────────────────────────────────────────────────────
        ALLOWED.put(COMPLETED,     EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(DEBIT_FAILED,  EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(REVERSED,      EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(FAILED,        EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(MANUAL_REVIEW, EnumSet.noneOf(TransactionStatus.class));
    }

    /**
     * Assert that {@code from -> to} is legal. Throws otherwise.
     * O(1). Called before every single state assignment in the system.
     */
    public void transition(TransactionStatus from, TransactionStatus to) {
        Set<TransactionStatus> allowed = ALLOWED.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new InvalidStateTransitionException(
                    String.format("Invalid transition: %s -> %s (allowed from %s: %s)",
                            from, to, from, allowed));
        }
    }

    /** True if {@code from -> to} is legal, without throwing. */
    public boolean canTransition(TransactionStatus from, TransactionStatus to) {
        Set<TransactionStatus> allowed = ALLOWED.get(from);
        return allowed != null && allowed.contains(to);
    }

    /** A state with no outgoing edges. The payment is finished, either way. */
    public boolean isTerminal(TransactionStatus state) {
        Set<TransactionStatus> next = ALLOWED.get(state);
        return next == null || next.isEmpty();
    }

    /** Exposed for the API and the showcase, so the UI never hard-codes the graph. */
    public Set<TransactionStatus> getAllowedNextStates(TransactionStatus from) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(TransactionStatus.class));
    }
}
