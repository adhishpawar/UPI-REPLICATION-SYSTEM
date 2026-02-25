package com.upi.psp.exception;

public class TokenExpiredException extends RuntimeException{
    public TokenExpiredException(String message)
    {
        super(message);
    }
}
