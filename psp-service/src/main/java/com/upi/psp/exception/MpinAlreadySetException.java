package com.upi.psp.exception;

// ─────────────────────────────────────────────────────────────────────────────
// MpinAlreadySetException
// ROLE: Thrown when setup-mpin is called on an account that's already ACTIVE.
//       Prevents overwriting MPIN without going through a secure reset flow.
// MAPS TO: HTTP 400 Bad Request
// THROWN IN: AuthServiceImpl.setupMpin()
// ─────────────────────────────────────────────────────────────────────────────
public class MpinAlreadySetException extends AuthBaseException {
    public MpinAlreadySetException(String message) {
        super(message, "MPIN_ALREADY_SET");
    }
}
