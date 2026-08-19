package com.upi.payment.exception;

// ─────────────────────────────────────────────────────────────────────────────
// SelfPaymentException
// THROWN BY: PaymentServiceImpl — payer and payee VPA are the same
// MAPS TO:   HTTP 400 Bad Request
// NOTE: The DB constraint chk_payer_payee_diff is the last line of defence.
// ─────────────────────────────────────────────────────────────────────────────
public class SelfPaymentException extends PaymentBaseException {
    public SelfPaymentException() {
        super("Payer and payee VPA cannot be the same", "SELF_PAYMENT_NOT_ALLOWED");
    }
}
