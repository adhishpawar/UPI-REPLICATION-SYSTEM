package com.upi.psp.exception;

// ─────────────────────────────────────────────────────────────────────────────
// InvalidCredentialsException
// ROLE: Thrown on failed login — wrong MPIN, or user+device not found.
//       Deliberately generic message to prevent user enumeration attacks.
//       (Attacker should NOT know if "mobile doesn't exist" vs "MPIN wrong".)
// MAPS TO: HTTP 401 Unauthorized
// THROWN IN: AuthServiceImpl.login()
// ─────────────────────────────────────────────────────────────────────────────
public class InvalidCredentialsException extends AuthBaseException {
    public InvalidCredentialsException() {
        super("Invalid credentials. Please check your mobile number and MPIN.", "INVALID_CREDENTIALS");
    }
    public InvalidCredentialsException(String message) {
        super(message, "INVALID_CREDENTIALS");
    }
}
