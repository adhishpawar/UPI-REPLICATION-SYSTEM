package com.upi.payment.statemachine;


import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.exception.InvalidStateTransitionException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class TransactionStateMachine {

    // ALGORITHM: EnumMap<State, EnumSet<AllowedNextStates>>
    // EnumMap: O(1) lookup, backed by array indexed by enum ordinal.
    //          More memory-efficient than HashMap for enum keys.
    // EnumSet: O(1) contains(), packed into a single long bitmask.
    //          64-bit long can hold up to 64 enum values — perfect here.
    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED_TRANSITIONS;

    static {
        ALLOWED_TRANSITIONS = new EnumMap<>(TransactionStatus.class);

        //Every Valid Transition: from state -> set of valid next states
        ALLOWED_TRANSITIONS.put(TransactionStatus.INITIATED,
                EnumSet.of(TransactionStatus.PAYEE_VALIDATED,
                        TransactionStatus.FAILED));  //VPA lookup fail

        ALLOWED_TRANSITIONS.put(TransactionStatus.PAYEE_VALIDATED,
                EnumSet.of(TransactionStatus.DEBIT_REQUESTED,
                        TransactionStatus.FAILED));

        ALLOWED_TRANSITIONS.put(TransactionStatus.DEBIT_REQUESTED,
                EnumSet.of(TransactionStatus.DEBITED,
                        TransactionStatus.DEBIT_FAILED));

        ALLOWED_TRANSITIONS.put(TransactionStatus.DEBITED,
                EnumSet.of(TransactionStatus.CREDIT_REQUESTED,
                        TransactionStatus.FAILED));

        ALLOWED_TRANSITIONS.put(TransactionStatus.CREDIT_REQUESTED,
                EnumSet.of(TransactionStatus.CREDITED,
                        TransactionStatus.CREDIT_FAILED));

        ALLOWED_TRANSITIONS.put(TransactionStatus.CREDITED,
                EnumSet.of(TransactionStatus.COMPLETED));

        ALLOWED_TRANSITIONS.put(TransactionStatus.CREDIT_FAILED,
                EnumSet.of(TransactionStatus.REVERSAL_INITIATED));

        ALLOWED_TRANSITIONS.put(TransactionStatus.REVERSAL_INITIATED,
                EnumSet.of(TransactionStatus.REVERSED,
                        TransactionStatus.FAILED));

        //Terminal states - no outgoing Transitions
        ALLOWED_TRANSITIONS.put(TransactionStatus.COMPLETED,
                EnumSet.noneOf(TransactionStatus.class));

        ALLOWED_TRANSITIONS.put(TransactionStatus.DEBIT_FAILED,
                EnumSet.noneOf(TransactionStatus.class));

        ALLOWED_TRANSITIONS.put(TransactionStatus.REVERSED,
                EnumSet.noneOf(TransactionStatus.class));

        ALLOWED_TRANSITIONS.put(TransactionStatus.FAILED,
                EnumSet.noneOf(TransactionStatus.class));
    }
        /*
        * validate and execute a state transition.
        * Guard Condition --> FromState must be in ALLOWED_TRANSITIONS
        * toState must be in the allowed-next sey for fromState
        *
        * TC --> O(1)
        * */

        public void transition(TransactionStatus from, TransactionStatus to)
        {
            Set<TransactionStatus> allowed = ALLOWED_TRANSITIONS.get(from);

            if(allowed == null || !allowed.contains(to))
            {
                throw new InvalidStateTransitionException(
                        String.format("Invalid transition: %s → %s", from, to));
            }
        }

        //Check if a state is terminal (no further transitions possible)
        public boolean isTerminal(TransactionStatus state)
        {
            Set<TransactionStatus> next = ALLOWED_TRANSITIONS.get(state);
            return next == null || next.isEmpty();
        }

        //Get all valid next states for a Given state (For Docs and UI)
        public Set<TransactionStatus> getAllowedNextStates(TransactionStatus from)
        {
            return ALLOWED_TRANSITIONS.getOrDefault(from,
                    EnumSet.noneOf(TransactionStatus.class));
        }
}
