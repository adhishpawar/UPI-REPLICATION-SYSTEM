package com.upi.psp.service;

// ─────────────────────────────────────────────────────────────────────────────
// ROLE: Service interface — the contract defining WHAT the auth service does,
//       not HOW it does it.
//
// LOGIC / WHY INTERFACE:
//   1. TESTABILITY: In unit tests, we use @MockBean AuthService — we inject a
//      Mockito mock of this interface into AuthController tests. If AuthController
//      depended on AuthServiceImpl directly, we'd need the full Spring context
//      and a real database running in every test.
//
//   2. OPEN/CLOSED PRINCIPLE: If we ever need a different implementation
//      (e.g. OAuthServiceImpl for social login), we add a new class implementing
//      this interface without changing AuthController at all.
//
//   3. DEPENDENCY INVERSION: AuthController depends on this abstraction, not a
//      concrete class. High-level modules (controller) shouldn't depend on
//      low-level modules (service impl) — they should both depend on abstractions.
//
// JAVA CONCEPT: Interface methods are implicitly public abstract. No need to
//   write 'public abstract' — just the return type and method name.
// ─────────────────────────────────────────────────────────────────────────────

import com.upi.psp.domain.dto.*;

import javax.security.auth.login.AccountLockedException;

public interface AuthService {

    /**
     * Register a new user with mobile number and device binding.
     * Status will be PENDING_MPIN after registration.
     *
     * @param request validated registration details
     * @return response with userId and masked mobile
     * @throws com.upi.psp.exception.UserAlreadyExistsException if mobile+device combo already registered
     */
    UserRegistrationResponse registerUser(UserRegistrationRequest request);

    /**
     * Set MPIN for a newly registered user.
     * Transitions user status from PENDING_MPIN → ACTIVE.
     * The raw MPIN is BCrypt-hashed before storage. Raw MPIN is never saved.
     *
     * @param request contains userId and raw MPIN (4-6 digits)
     * @throws com.upi.psp.exception.UserNotFoundException if userId not found
     * @throws com.upi.psp.exception.MpinAlreadySetException if status is not PENDING_MPIN
     */
    void setupMpin(MpinSetupRequest request);

    /**
     * Authenticate user with mobile + device + MPIN.
     * Returns a signed RS256 JWT on success.
     * Rate-limited: max 5 attempts per mobile per 15 minutes.
     * Auto-locks account after 10 consecutive failures.
     *
     * @param request login credentials
     * @return JWT access token and metadata
     * @throws com.upi.psp.exception.TooManyLoginAttemptsException if rate limit exceeded
     * @throws com.upi.psp.exception.InvalidCredentialsException if MPIN wrong or user not found
     * @throws com.upi.psp.exception.AccountLockedException if account is locked or suspended
     */
    LoginResponse login(LoginRequest request) throws AccountLockedException;

    /**
     * Revoke a JWT. Marks the token record as revoked in auth_tokens table.
     * Subsequent validation of the same token returns invalid.
     *
     * @param rawToken the raw JWT string from Authorization header
     */
    void logout(String rawToken);

    /**
     * Validate a JWT and extract its claims.
     * Called by API Gateway before forwarding requests downstream.
     * Pure CPU operation — no DB call for signature verification.
     * DB check only for token revocation status.
     *
     * @param rawToken the raw JWT string
     * @return validation result with extracted userId and deviceId
     */
    TokenValidationResponse validateToken(String rawToken);
}
