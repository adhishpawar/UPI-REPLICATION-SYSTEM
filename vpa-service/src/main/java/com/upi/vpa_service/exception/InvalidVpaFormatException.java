package com.upi.vpa_service.exception;

import java.util.StringJoiner;

public class InvalidVpaFormatException extends VpaBaseException{
    public InvalidVpaFormatException(String message)
    {
        super(message, "VPA_INVALID_FORMAT");
    }
}
