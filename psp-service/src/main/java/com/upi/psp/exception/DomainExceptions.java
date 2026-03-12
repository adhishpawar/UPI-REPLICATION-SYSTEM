package com.upi.psp.exception;

import java.util.UUID;

// ═════════════════════════════════════════════════════════════════════════════
// FILE: All domain exceptions for PSP Service
// Each class is in its own conceptual block but kept here for conciseness.
// In a large team, split each into its own file.
// ═════════════════════════════════════════════════════════════════════════════


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


// ─────────────────────────────────────────────────────────────────────────────
// AccountLockedException
// ROLE: Thrown when a LOCKED or SUSPENDED user attempts to login.
//       Auto-lock happens after 10 consecutive failed MPIN attempts.
// MAPS TO: HTTP 403 Forbidden
// THROWN IN: AuthServiceImpl.login()
// ─────────────────────────────────────────────────────────────────────────────
class AccountLockedException extends AuthBaseException {
    public AccountLockedException(String message) {
        super(message, "ACCOUNT_LOCKED");
    }
}


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


// ─────────────────────────────────────────────────────────────────────────────
// TokenExpiredException
// ROLE: Thrown when JwtTokenProvider validates an expired JWT.
//       Signals to the client that they need to login again (or use refresh token).
// MAPS TO: HTTP 401 Unauthorized
// THROWN IN: JwtTokenProvider.validateAndExtractClaims()
// ─────────────────────────────────────────────────────────────────────────────
public class TokenExpiredException extends AuthBaseException {
    public TokenExpiredException(String message) {
        super(message, "TOKEN_EXPIRED");
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// InvalidTokenException
// ROLE: Thrown when a JWT has an invalid signature, wrong issuer,
//       malformed structure, or has been revoked.
// MAPS TO: HTTP 401 Unauthorized
// THROWN IN: JwtTokenProvider.validateAndExtractClaims()
//            AuthServiceImpl.validateToken() — revocation check
// ─────────────────────────────────────────────────────────────────────────────
public class InvalidTokenException extends AuthBaseException {
    public InvalidTokenException(String message) {
        super(message, "INVALID_TOKEN");
    }
}


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
