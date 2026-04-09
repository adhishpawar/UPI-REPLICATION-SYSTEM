package com.upi.payment.exception;

//DuplicatePaymentException
//Thrown By --> PayService initPayment -> DB constraint Violation fallback
//Maps to 409 Conflict
//Normally caught by IdempotencyFilter before reaching service
//This is the fallback for race conditions between multiple Instances
public class DuplicatePaymentException extends PaymentBaseException
{
    public DuplicatePaymentException(String idempotencyKey)
    {
        super("Duplicate Payment: idempotency key already processed: " + idempotencyKey, "DUPLICATE_PAYMENT");
    }
}
