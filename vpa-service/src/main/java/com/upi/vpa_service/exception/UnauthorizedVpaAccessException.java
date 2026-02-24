package com.upi.vpa_service.exception;

public class UnauthorizedVpaAccessException extends VpaBaseException{

    public UnauthorizedVpaAccessException(String vpaAddress) {
        super(
                "User is not authorized to access VPA: " + vpaAddress,
                "VPA_UNAUTHORIZED_ACCESS"
        );
    }
}