package com.upi.payment.exception;

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


