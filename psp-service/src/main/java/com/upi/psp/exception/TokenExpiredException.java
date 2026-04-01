package com.upi.psp.exception;

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
