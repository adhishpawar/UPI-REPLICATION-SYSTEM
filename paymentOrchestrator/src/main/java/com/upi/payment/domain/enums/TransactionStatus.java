package com.upi.payment.domain.enums;

public enum TransactionStatus {
    INITIATED,
    PAYEE_VALIDATED,
    FAILED,
    DEBIT_REQUESTED,
    DEBIT_FAILED,
    DEBITED,
    CREDIT_REQUESTED,
    CREDITED,
    CREDIT_FAILED,
    COMPLETED,
    REVERSAL_INITIATED,
    REVERSED

}
