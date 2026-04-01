package com.upi.psp.exception;

// ─────────────────────────────────────────────────────────────────────────────
// AccountLockedException
// ROLE: Thrown when a LOCKED or SUSPENDED user attempts to login.
//       Auto-lock happens after 10 consecutive failed MPIN attempts.
// MAPS TO: HTTP 403 Forbidden
// THROWN IN: AuthServiceImpl.login()
// ─────────────────────────────────────────────────────────────────────────────
public class AccountLockedException extends AuthBaseException {
    public AccountLockedException(String message) {
        super(message, "ACCOUNT_LOCKED");
    }
}
