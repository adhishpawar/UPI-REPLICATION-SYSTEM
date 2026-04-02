package com.upi.psp.exception;

// ─────────────────────────────────────────────────────────────────────────────
// TooManyLoginAttemptsException
// ROLE: Thrown when the Bucket4j rate limiter rejects a login attempt.
//       Different from AccountLockedException — this is per-window throttling,
//       not permanent locking.
// MAPS TO: HTTP 429 Too Many Requests
// THROWN IN: AuthServiceImpl.login() — rate limiter check first line
// ─────────────────────────────────────────────────────────────────────────────
public class TooManyLoginAttemptsException extends AuthBaseException {
    public TooManyLoginAttemptsException(String message) {
        super(message, "TOO_MANY_ATTEMPTS");
    }
}
