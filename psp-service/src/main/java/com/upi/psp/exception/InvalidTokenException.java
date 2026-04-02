package com.upi.psp.exception;

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
