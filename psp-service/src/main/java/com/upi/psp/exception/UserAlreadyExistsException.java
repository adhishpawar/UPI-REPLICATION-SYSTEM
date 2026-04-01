package com.upi.psp.exception;

// ═════════════════════════════════════════════════════════════════════════════
// FILE: All domain exceptions for PSP Service
// Each class is in its own conceptual block but kept here for conciseness.
// In a large team, split each into its own file.
// ═════════════════════════════════════════════════════════════════════════════


// ─────────────────────────────────────────────────────────────────────────────
// UserAlreadyExistsException
// ROLE: Thrown when a mobile+device combo is already registered.
// MAPS TO: HTTP 409 Conflict
// THROWN IN: AuthServiceImpl.registerUser()
// ─────────────────────────────────────────────────────────────────────────────
public class UserAlreadyExistsException extends AuthBaseException {
    public UserAlreadyExistsException(String message) {
        super(message, "USER_ALREADY_EXISTS");
    }
}


