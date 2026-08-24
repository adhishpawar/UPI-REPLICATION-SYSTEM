package com.upi.payment.exception;

// ─────────────────────────────────────────────────────────────────────────────
// InvalidStateTransitionException
// THROWN BY: TransactionStateMachine.transition()
// MAPS TO:   HTTP 409 Conflict (if triggered by API) / logged as ERROR (if from Kafka)
// NOTE: If this is ever thrown from a Kafka consumer, it indicates a bug —
//       the Kafka message ordering is wrong. Alert immediately.
// ─────────────────────────────────────────────────────────────────────────────
public class InvalidStateTransitionException extends PaymentBaseException {
    public InvalidStateTransitionException(String message) {
        super(message, "INVALID_STATE_TRANSITION");
    }
}
