package com.upi.payment.exception;

// ─────────────────────────────────────────────────────────────────────────────
// VpaNotFoundException
// THROWN BY: VpaServiceClient (wraps 404 from VPA Service)
// CAUGHT BY: SagaOrchestrator.validatePayee() → transitions to FAILED
// MAPS TO:   Not directly — caught internally. Transaction ends in FAILED state.
// ─────────────────────────────────────────────────────────────────────────────
public class VpaNotFoundException extends PaymentBaseException {
    public VpaNotFoundException(String vpaAddress) {
        super("VPA not found or inactive: " + vpaAddress, "VPA_NOT_FOUND");
    }
}
