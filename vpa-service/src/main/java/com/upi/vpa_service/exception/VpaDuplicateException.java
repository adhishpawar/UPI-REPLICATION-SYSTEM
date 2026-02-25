package com.upi.vpa_service.exception;

public class VpaDuplicateException extends VpaBaseException{
    public VpaDuplicateException(String vpaAddress)
    {
        super("VPA already registered: " + vpaAddress, "VPA_DUPLICATE");
    }
}
