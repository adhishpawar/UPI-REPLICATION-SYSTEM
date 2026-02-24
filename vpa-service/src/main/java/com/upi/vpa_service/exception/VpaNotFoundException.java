package com.upi.vpa_service.exception;

public class VpaNotFoundException extends VpaBaseException{
    public VpaNotFoundException(String vpaAddress)
    {
        super("VPA not found or inactive: " + vpaAddress, "VPA_NOT_FOUND");
    }
}
