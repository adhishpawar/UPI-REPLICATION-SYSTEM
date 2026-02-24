package com.upi.vpa_service.exception;

public abstract class VpaBaseException extends RuntimeException{
    private final String errorCode;
    public VpaBaseException(String message, String errorCode)
    {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode()
    {
        return errorCode;
    }
}
