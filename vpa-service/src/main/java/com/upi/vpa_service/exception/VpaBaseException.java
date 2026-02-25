package com.upi.vpa_service.exception;

import lombok.Getter;

@Getter
public abstract class VpaBaseException extends RuntimeException{
    private final String errorCode;
    public VpaBaseException(String message, String errorCode)
    {
        super(message);
        this.errorCode = errorCode;
    }

}
