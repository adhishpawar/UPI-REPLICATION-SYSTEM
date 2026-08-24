package com.upi.payment.exception;

import java.util.UUID;

//Transaction Not Found
//Thrown by --> PaymentServiceImpl --> get Transaction and Status
//Maps to --> 404 Not found
//Note: if txn exists but belong to the new different User --> 403 --> 404
public class TransactionNotFoundException extends PaymentBaseException{
    public TransactionNotFoundException(UUID transactionId){
        super("Transaction not found: " + transactionId, "TRANSACTION_NOT_FOUND");
    }
}
