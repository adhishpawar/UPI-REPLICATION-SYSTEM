package com.upi.psp.exception;

// ─────────────────────────────────────────────────────────────────────────────
// MpinNotSetException
// ROLE: Thrown when a user in PENDING_MPIN status tries to login.
//       Tells the client to redirect user to the MPIN setup screen.
// MAPS TO: HTTP 403 Forbidden
// THROWN IN: AuthServiceImpl.login()
// ─────────────────────────────────────────────────────────────────────────────
public class MpinNotSetException extends AuthBaseException {
    public MpinNotSetException(String message) {
        super(message, "MPIN_NOT_SET");
    }
}
