package com.upi.psp.service;

import com.upi.psp.domain.dto.TokenValidationResponse;
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
