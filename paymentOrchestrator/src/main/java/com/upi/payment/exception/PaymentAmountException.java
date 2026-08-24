package com.upi.payment.exception;

// ─────────────────────────────────────────────────────────────────────────────
// PaymentAmountException
// THROWN BY: Validation layer (before service)
// MAPS TO:   HTTP 400 Bad Request
// COVERS:    Amount = 0, amount > ₹2 Lakh NPCI limit, invalid decimals
// ─────────────────────────────────────────────────────────────────────────────
public class PaymentAmountException extends PaymentBaseException {
    public PaymentAmountException(String message) {
        super(message, "INVALID_PAYMENT_AMOUNT");
    }
}
