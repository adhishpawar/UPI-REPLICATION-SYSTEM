package com.upi.psp.exception;

// ─────────────────────────────────────────────────────────────────────────────
// InvalidMobileNumberException
// ROLE: Thrown when the mobile number cannot be normalized to E.164 format.
//       Caught by GlobalExceptionHandler → 400 Bad Request.
// THROWN IN: AuthServiceImpl.normalizeMobile()
// ─────────────────────────────────────────────────────────────────────────────
public class InvalidMobileNumberException extends AuthBaseException {
    public InvalidMobileNumberException(String message) {
        super(message, "INVALID_MOBILE_NUMBER");
    }
}
