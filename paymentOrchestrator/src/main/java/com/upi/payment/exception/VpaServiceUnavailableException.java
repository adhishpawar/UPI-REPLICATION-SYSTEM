package com.upi.payment.exception;

// ─────────────────────────────────────────────────────────────────────────────
// VpaServiceUnavailableException
// THROWN BY: VpaServiceClient fallback (when circuit breaker is OPEN)
// CAUGHT BY: SagaOrchestrator.validatePayee() → transitions to FAILED
// MAPS TO:   Not directly. Transaction ends in FAILED state with clear reason.
// ─────────────────────────────────────────────────────────────────────────────
public class VpaServiceUnavailableException extends PaymentBaseException {
    public VpaServiceUnavailableException(String message) {
        super(message, "VPA_SERVICE_UNAVAILABLE");
    }
}
