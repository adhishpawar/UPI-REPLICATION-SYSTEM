package com.upi.payment.exception;

import java.util.UUID;

//Exception hierarchy for payment Orchestrator

//Role is --> Abstract root of all payment Orchestrator custom Exceptions
//Carries a M/C readable errorCode for consistent API response
//Extends RuntimeException (unchecked) -> @Transactional auto-rollback


abstract class PaymentBaseException  extends RuntimeException{

    private final String errorCode;

    protected PaymentBaseException(String message, String errorCode)
    {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {return  errorCode; }
}

//Transaction Not Found
//Thrown by --> PaymentServiceImpl --> get Transaction and Status
//Maps to --> 404 Not found
//Note: if txn exists but belong to the new different User --> 403 --> 404
class TransactionNotFoundException extends PaymentBaseException{
    public TransactionNotFoundException(UUID transactionId){
        super("Transaction not found: " + transactionId, "TRANSACTION_NOT_FOUND");
    }
}


//DuplicatePaymentException
//Thrown By --> PayService initPayment -> DB constraint Violation fallback
//Maps to 409 Conflict
//Normally caught by IdempotencyFilter before reaching service
//This is the fallback for race conditions between multiple Instances
class DuplicatePaymentException extends PaymentBaseException
{
    public DuplicatePaymentException(String idempotencyKey)
    {
        super("Duplicate Payment: idempotency key already processed: " + idempotencyKey, "DUPLICATE_PAYMENT");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// InvalidStateTransitionException
// THROWN BY: TransactionStateMachine.transition()
// MAPS TO:   HTTP 409 Conflict (if triggered by API) / logged as ERROR (if from Kafka)
// NOTE: If this is ever thrown from a Kafka consumer, it indicates a bug —
//       the Kafka message ordering is wrong. Alert immediately.
// ─────────────────────────────────────────────────────────────────────────────
class InvalidStateTransitionException extends PaymentBaseException {
    public InvalidStateTransitionException(String message) {
        super(message, "INVALID_STATE_TRANSITION");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VpaNotFoundException
// THROWN BY: VpaServiceClient (wraps 404 from VPA Service)
// CAUGHT BY: SagaOrchestrator.validatePayee() → transitions to FAILED
// MAPS TO:   Not directly — caught internally. Transaction ends in FAILED state.
// ─────────────────────────────────────────────────────────────────────────────
class VpaNotFoundException extends PaymentBaseException {
    public VpaNotFoundException(String vpaAddress) {
        super("VPA not found or inactive: " + vpaAddress, "VPA_NOT_FOUND");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VpaServiceUnavailableException
// THROWN BY: VpaServiceClient fallback (when circuit breaker is OPEN)
// CAUGHT BY: SagaOrchestrator.validatePayee() → transitions to FAILED
// MAPS TO:   Not directly. Transaction ends in FAILED state with clear reason.
// ─────────────────────────────────────────────────────────────────────────────
class VpaServiceUnavailableException extends PaymentBaseException {
    public VpaServiceUnavailableException(String message) {
        super(message, "VPA_SERVICE_UNAVAILABLE");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PaymentAmountException
// THROWN BY: Validation layer (before service)
// MAPS TO:   HTTP 400 Bad Request
// COVERS:    Amount = 0, amount > ₹2 Lakh NPCI limit, invalid decimals
// ─────────────────────────────────────────────────────────────────────────────
class PaymentAmountException extends PaymentBaseException {
    public PaymentAmountException(String message) {
        super(message, "INVALID_PAYMENT_AMOUNT");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SelfPaymentException
// THROWN BY: PaymentServiceImpl — payer and payee VPA are the same
// MAPS TO:   HTTP 400 Bad Request
// NOTE: The DB constraint chk_payer_payee_diff is the last line of defence.
// ─────────────────────────────────────────────────────────────────────────────
class SelfPaymentException extends PaymentBaseException {
    public SelfPaymentException() {
        super("Payer and payee VPA cannot be the same", "SELF_PAYMENT_NOT_ALLOWED");
    }
}

