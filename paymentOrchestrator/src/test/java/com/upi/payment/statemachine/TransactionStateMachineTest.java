package com.upi.payment.statemachine;

import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.upi.payment.domain.enums.TransactionStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the state machine.
 *
 * <p>These are not tests that the code does what it does. They are tests that
 * the <em>money invariants</em> hold, expressed as properties of the state
 * graph. In a payment system the tests are the specification: "it worked when
 * I clicked it" is not evidence that money cannot be created.
 *
 * <p>Several are written as graph-wide properties rather than examples, so
 * that adding a state later cannot quietly violate them. A test that only
 * checks the transitions someone thought of is a test that stops protecting
 * the system the moment someone adds an edge.
 */
class TransactionStateMachineTest {

    private final TransactionStateMachine sm = new TransactionStateMachine();

    // ── The happy path ────────────────────────────────────────────────────

    @Test
    @DisplayName("the full success path is walkable end to end")
    void happyPathIsWalkable() {
        List<TransactionStatus> path = List.of(
                INITIATED, PAYEE_VALIDATED, DEBIT_REQUESTED,
                DEBITED, CREDIT_REQUESTED, CREDITED, COMPLETED);

        for (int i = 0; i < path.size() - 1; i++) {
            int step = i;
            assertDoesNotThrow(() -> sm.transition(path.get(step), path.get(step + 1)),
                    path.get(step) + " -> " + path.get(step + 1) + " must be legal");
        }
    }

    @Test
    @DisplayName("the saga's original defect is still rejected")
    void skippingTheRequestStateIsRejected() {
        // The saga once published a debit command while leaving the payment in
        // PAYEE_VALIDATED, then tried to record the reply as PAYEE_VALIDATED ->
        // DEBITED. The guard caught it and the happy path could not complete.
        // Keeping the edge illegal is what makes that a loud failure rather
        // than a payment whose recorded state never matched what was asked for.
        assertThrows(InvalidStateTransitionException.class,
                () -> sm.transition(PAYEE_VALIDATED, DEBITED));
        assertThrows(InvalidStateTransitionException.class,
                () -> sm.transition(DEBITED, CREDITED));
    }

    // ── Uncertainty ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("uncertainty")
    class Uncertainty {

        @Test
        @DisplayName("every in-flight funds movement can become UNCERTAIN")
        void everyInFlightStateCanBecomeUncertain() {
            // If a state can be waiting for a money-holder's reply, it must be
            // able to represent "we did not get one". A state that cannot
            // forces a timeout to be recorded as success or failure, and both
            // of those create or destroy money.
            for (TransactionStatus inFlight : List.of(
                    DEBIT_REQUESTED, CREDIT_REQUESTED, REVERSAL_INITIATED)) {
                assertTrue(sm.canTransition(inFlight, inFlight.uncertainCounterpart()),
                        inFlight + " must be able to become "
                                + inFlight.uncertainCounterpart());
            }
        }

        @Test
        @DisplayName("an uncertain state can only be left through its reconciling state")
        void uncertainOnlyLeadsToReconciling() {
            // The single most important edge constraint in the graph. If an
            // uncertain state could go straight to COMPLETED or REVERSED, some
            // code path could decide the outcome of a payment without
            // establishing any facts -- exactly the guess this design exists
            // to prevent.
            for (TransactionStatus u : List.of(UNCERTAIN_DEBIT, UNCERTAIN_CREDIT, UNCERTAIN_REVERSAL)) {
                assertEquals(Set.of(u.reconcilingCounterpart()), sm.getAllowedNextStates(u),
                        u + " must lead only to " + u.reconcilingCounterpart());
            }
        }

        @Test
        @DisplayName("reconciling the debit can resume or fail safely, but never complete")
        void reconcilingDebitConclusions() {
            Set<TransactionStatus> next = sm.getAllowedNextStates(RECONCILING_DEBIT);
            assertTrue(next.contains(DEBITED),         "debit found -> resume");
            assertTrue(next.contains(DEBIT_FAILED),    "debit missing -> fail, no money moved");
            assertTrue(next.contains(UNCERTAIN_DEBIT), "unreachable -> stay unsure");
            assertTrue(next.contains(MANUAL_REVIEW),   "out of attempts -> escalate");
            assertFalse(next.contains(COMPLETED),
                    "a payment cannot complete while its debit is still in doubt");
        }

        @Test
        @DisplayName("reconciling the credit must account for money already debited")
        void reconcilingCreditConclusions() {
            Set<TransactionStatus> next = sm.getAllowedNextStates(RECONCILING_CREDIT);
            assertTrue(next.contains(COMPLETED),          "credit found -> complete");
            assertTrue(next.contains(REVERSAL_INITIATED), "credit missing -> compensate");
            assertTrue(next.contains(MANUAL_REVIEW),      "out of attempts -> escalate");
            assertFalse(next.contains(DEBIT_FAILED),
                    "the debit is already confirmed; it can never be recorded as failed");
        }
    }

    // ── Graph-wide money invariants ───────────────────────────────────────

    @Nested
    @DisplayName("money invariants")
    class MoneyInvariants {

        @Test
        @DisplayName("no state after the debit can end without resolving the money")
        void everyPostDebitStateReachesAResolution() {
            // Once money has left the payer, a payment may not simply stop. It
            // must end paid (COMPLETED), refunded (REVERSED), or in a human's
            // queue (MANUAL_REVIEW). "FAILED" is not an acceptable ending
            // there, because it says nothing about where the money went.
            Set<TransactionStatus> acceptable = Set.of(COMPLETED, REVERSED, MANUAL_REVIEW);
            // DEBIT_FAILED is deliberately absent: it asserts that no money
            // moved, which is false once the debit is confirmed.

            for (TransactionStatus s : TransactionStatus.values()) {
                if (!s.isAfterMoneyMoved()) continue;

                Set<TransactionStatus> terminals = reachableTerminals(s);
                assertFalse(terminals.isEmpty(), s + " must be able to reach a terminal state");
                assertTrue(acceptable.containsAll(terminals),
                        s + " can end in " + terminals + ", which includes an ending that "
                        + "does not account for money already moved");
            }
        }

        @Test
        @DisplayName("no state can reach a dead end")
        void noStateIsStranded() {
            // A non-terminal state with no route to a terminal one is a
            // payment that can never finish. Nobody would be told; it would
            // simply sit there.
            for (TransactionStatus s : TransactionStatus.values()) {
                if (sm.isTerminal(s)) continue;
                assertFalse(reachableTerminals(s).isEmpty(),
                        s + " cannot reach any terminal state - payments entering it are stranded");
            }
        }

        @Test
        @DisplayName("terminal states have no way out")
        void terminalStatesAreFinal() {
            for (TransactionStatus s : List.of(COMPLETED, REVERSED, FAILED, DEBIT_FAILED, MANUAL_REVIEW)) {
                assertTrue(sm.isTerminal(s), s + " must be terminal");
                assertTrue(sm.getAllowedNextStates(s).isEmpty(),
                        s + " must have no outgoing transitions");
            }
        }

        @Test
        @DisplayName("a failed reversal is not treated as a finished payment")
        void reversalFailureIsNotTerminal() {
            // The payer's money is still missing. Marking this terminal would
            // close the case on someone who is out of pocket.
            assertFalse(sm.isTerminal(REVERSAL_FAILED));
            assertTrue(sm.getAllowedNextStates(REVERSAL_FAILED).contains(MANUAL_REVIEW));
        }

        @Test
        @DisplayName("pre-money failures are terminal and safe")
        void preMoneyFailuresAreTerminal() {
            assertFalse(FAILED.isAfterMoneyMoved());
            assertFalse(DEBIT_FAILED.isAfterMoneyMoved());
            assertTrue(sm.isTerminal(FAILED));
            assertTrue(sm.isTerminal(DEBIT_FAILED));
        }

        @Test
        @DisplayName("DEBITED marks the boundary where money has moved")
        void debitedIsTheBoundary() {
            assertFalse(DEBIT_REQUESTED.isAfterMoneyMoved(),
                    "a requested debit has not necessarily happened");
            assertTrue(DEBITED.isAfterMoneyMoved(),
                    "a confirmed debit means money has left the payer");
        }
    }

    // ── Guard behaviour ───────────────────────────────────────────────────

    @Test
    @DisplayName("an illegal transition throws and names both states")
    void illegalTransitionIsExplicit() {
        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> sm.transition(COMPLETED, REVERSED));
        assertTrue(ex.getMessage().contains("COMPLETED"));
        assertTrue(ex.getMessage().contains("REVERSED"));
    }

    @Test
    @DisplayName("every state is present in the graph")
    void everyStateIsDeclared() {
        // A state added to the enum but not to the transition map would have a
        // null entry, making every transition out of it illegal and every
        // payment reaching it stranded -- with no compile-time warning.
        for (TransactionStatus s : TransactionStatus.values()) {
            assertNotNull(sm.getAllowedNextStates(s), s + " is missing from the graph");
        }
    }

    // ── helper ────────────────────────────────────────────────────────────

    /** Breadth-first walk to every terminal state reachable from {@code start}. */
    private Set<TransactionStatus> reachableTerminals(TransactionStatus start) {
        Set<TransactionStatus> terminals = new HashSet<>();
        Set<TransactionStatus> seen = new HashSet<>();
        Deque<TransactionStatus> queue = new ArrayDeque<>(List.of(start));

        while (!queue.isEmpty()) {
            TransactionStatus s = queue.poll();
            if (!seen.add(s)) continue;
            if (sm.isTerminal(s)) {
                terminals.add(s);
                continue;
            }
            queue.addAll(new ArrayList<>(sm.getAllowedNextStates(s)));
        }
        return terminals;
    }
}
