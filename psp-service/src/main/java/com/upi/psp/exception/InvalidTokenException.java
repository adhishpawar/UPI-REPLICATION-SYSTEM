package com.upi.psp.exception;

public class InvalidTokenException extends RuntimeException{

    public InvalidTokenException(String message)
    {
        super(message);
    }
}
