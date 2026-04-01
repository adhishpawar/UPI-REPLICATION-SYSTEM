package com.upi.psp.exception;

import java.util.UUID;

// ─────────────────────────────────────────────────────────────────────────────
// UserNotFoundException
// ROLE: Thrown when a userId or mobile+device combo doesn't exist in the DB.
// MAPS TO: HTTP 404 Not Found
// THROWN IN: AuthServiceImpl.setupMpin(), and as fallback in login()
// SECURITY NOTE: In login(), we throw InvalidCredentialsException instead of
//   this, to avoid revealing whether a user account exists (user enumeration).
// ─────────────────────────────────────────────────────────────────────────────
public class UserNotFoundException extends AuthBaseException {
    public UserNotFoundException(UUID userId) {
        super("User not found with ID: " + userId, "USER_NOT_FOUND");
    }
    public UserNotFoundException(String message) {
        super(message, "USER_NOT_FOUND");
    }
}
