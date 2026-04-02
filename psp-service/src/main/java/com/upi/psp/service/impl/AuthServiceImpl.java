package com.upi.psp.service.impl;

import com.upi.psp.domain.dto.*;
import com.upi.psp.domain.entity.AuthToken;
import com.upi.psp.domain.entity.LoginAttempt;
import com.upi.psp.domain.entity.User;
import com.upi.psp.domain.enums.TokenType;
import com.upi.psp.domain.enums.UserStatus;
import com.upi.psp.exception.*;
import com.upi.psp.mapper.AuthMapper;
import com.upi.psp.ratelimiter.LoginRateLimiter;
import com.upi.psp.repo.AuthTokenRepository;
import com.upi.psp.repo.LoginAttemptRepository;
import com.upi.psp.repo.UserRepository;
import com.upi.psp.security.JwtTokenProvider;
import com.upi.psp.service.AuthService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final LoginAttemptService   loginAttemptService;
    private final UserStateService      userStateService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginRateLimiter rateLimiter;
    private final AuthMapper authMapper;

    @Override
    @Transactional
    public UserRegistrationResponse registerUser(UserRegistrationRequest request) {
        // Step 1: Normalize mobile number to E.164 format
        String normalizedMobile = normalizeMobile(request.getMobileNumber());

        // Step 2: Hash device fingerprint — never store raw fingerprint
        String deviceFpHash = hashDeviceFingerprint(request.getDeviceFingerprint());

        // Step 3: Check if this mobile+device combo already registered
        if (userRepository.existsByMobileNumberAndDeviceId(
                normalizedMobile, request.getDeviceId())) {
            throw new UserAlreadyExistsException(
                    "Device already registered for this mobile number");
        }

        // Step 4: Build and save user entity
        User user = new User();
        user.setMobileNumber(normalizedMobile);
        user.setDeviceId(request.getDeviceId());
        user.setDeviceFingerprint(deviceFpHash);
        user.setStatus(UserStatus.PENDING_MPIN);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        // mpinHash intentionally NOT set — null until setupMpin called

        User saved = userRepository.save(user);
        log.info("User registered: userId={}, mobile={}****",
                saved.getUserId(),
                normalizedMobile.substring(0, 6));  // Partial log only

        return authMapper.toRegistrationResponse(saved);
    }

    @Override
    @Transactional
    public void setupMpin(MpinSetupRequest request) {
        // SECURITY: We log userId but NEVER the MPIN itself
        log.info("MPIN setup for userId={}", request.getUserId());

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new UserNotFoundException(request.getUserId()));

        // Guard: only allow setup if in PENDING_MPIN state
        if (user.getStatus() != UserStatus.PENDING_MPIN) {
            throw new MpinAlreadySetException("MPIN already configured");
        }

        // CRITICAL: BCrypt hash the MPIN. Strength 12.
        // The raw MPIN (request.getMpin()) goes out of scope after this line
        user.setMpinHash(passwordEncoder.encode(request.getMpin()));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        // The request.getMpin() field should ideally be cleared from memory
        // In Java, Strings are immutable — can't zero them. Use char[] for
        // highest security (out of scope for this project but worth knowing)
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) throws AccountLockedException {
        String normalizedMobile = normalizeMobile(request.getMobileNumber());

        // Step 1: Rate limit check — Bucket4j O(1) lookup
        if (!rateLimiter.tryConsume(normalizedMobile)) {
            // Record failed attempt for audit
            loginAttemptService.recordAttempt(request.getMobileNumber(), request.getDeviceId(),
                    false, "RATE_LIMITED");
            throw new TooManyLoginAttemptsException(
                    "Too many login attempts. Try again in 15 minutes.");
        }

        // Step 2: Find user
        User user = userRepository
                .findByMobileNumberAndDeviceId(normalizedMobile, request.getDeviceId())
                .orElseThrow(() -> {
                    loginAttemptService.recordAttempt(request.getMobileNumber(), request.getDeviceId(),
                            false, "USER_NOT_FOUND");  // ← own txn, always commits
                    return new InvalidCredentialsException("Invalid credentials");
                    // Note: generic error message — don't reveal if user exists
                });

        // Step 3: Check account status
        if (!user.getIsActive() || user.getStatus() == UserStatus.LOCKED) {
            loginAttemptService.recordAttempt(normalizedMobile, request.getDeviceId(),
                    false, "ACCOUNT_LOCKED");
            throw new AccountLockedException("Account is locked or inactive");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            loginAttemptService.recordAttempt(normalizedMobile, request.getDeviceId(),
                    false, "MPIN_NOT_SET");
            throw new MpinNotSetException("MPIN setup not completed");
        }

        // Step 4: Verify MPIN — BCrypt.matches() is the only place MPIN is used
        // This takes ~300ms intentionally (BCrypt work factor 12)
        if (!passwordEncoder.matches(request.getMpin(), user.getMpinHash())) {
            // Increment failed count
            user.setFailedLoginCount(user.getFailedLoginCount() + 1);
            user.setLastFailedLoginAt(LocalDateTime.now());
            // Auto-lock after 10 failures
            if (user.getFailedLoginCount() >= 10) {
                user.setStatus(UserStatus.LOCKED);
                log.warn("Account auto-locked: userId={}", user.getUserId());
            }
            userStateService.recordFailedAttempt(user);   // ← own txn, commits failedCount + LOCKED
            loginAttemptService.recordAttempt(normalizedMobile, request.getDeviceId(),
                    false, "INVALID_MPIN");             // ← own txn, commits audit
            throw new InvalidCredentialsException("Invalid credentials");
        }

        // Step 5: Success path — everything below is in the parent txn ─────────
        userStateService.resetFailedCount(user);          // ← own txn
        loginAttemptService.recordAttempt(normalizedMobile, request.getDeviceId(),
                true, null);

        // Step 6: Generate JWT
        String rawToken = jwtTokenProvider.generateToken(
                user.getUserId(), user.getDeviceId());

        // Step 7: Store token record (hash only, not raw JWT)
        AuthToken tokenRecord = new AuthToken();
        tokenRecord.setUserId(user.getUserId());
        tokenRecord.setTokenHash(jwtTokenProvider.hashToken(rawToken));
        tokenRecord.setTokenType(TokenType.ACCESS);
        tokenRecord.setDeviceId(user.getDeviceId());
        tokenRecord.setIssuedAt(LocalDateTime.now());
        tokenRecord.setExpiresAt(LocalDateTime.now().plusSeconds(3600));
        authTokenRepository.save(tokenRecord);

        // Step 8: Record successful login
        recordLoginAttempt(normalizedMobile, request.getDeviceId(), true, null);

        log.info("Login successful: userId={}", user.getUserId());

        LoginResponse.LoginResponseBuilder builder = LoginResponse.builder();
        builder.accessToken(rawToken);
        builder.userId(user.getUserId());
        builder.tokenType("Bearer");
        builder.expiresIn(3600L);
        return builder
                .build();
    }

    @Override
    @Transactional
    public void logout(String rawToken) {
        String tokenHash = jwtTokenProvider.hashToken(rawToken);
        authTokenRepository.findByTokenHashAndIsRevokedFalse(tokenHash)
                .ifPresent(token -> {
                    token.setIsRevoked(true);
                    token.setRevokedAt(LocalDateTime.now());
                    authTokenRepository.save(token);
                });
    }

    public TokenValidationResponse validateToken(String rawToken) {

        // Step 1: Cryptographic validation — verifies RS256 signature + expiry
        // Throws TokenExpiredException or InvalidTokenException on failure
        Claims claims;
        try {
            claims = jwtTokenProvider.validateAndExtractClaims(rawToken);
        } catch (TokenExpiredException | InvalidTokenException ex) {
            // Return a valid response object with valid=false (don't throw here —
            // the Gateway expects a 200 response with valid=false, not a 401)
            return TokenValidationResponse.builder()
                    .valid(false)
                    .invalidReason(ex.getMessage())
                    .build();
        }

        // Step 2: Check revocation status in DB
        // A token can be cryptographically valid but revoked (user logged out)
        String tokenHash = jwtTokenProvider.hashToken(rawToken);
        boolean isRevoked = authTokenRepository
                .findByTokenHashAndIsRevokedFalse(tokenHash)
                .isEmpty(); // empty → not found in active tokens → revoked or never stored

        if (isRevoked) {
            return TokenValidationResponse.builder()
                    .valid(false)
                    .invalidReason("Token has been revoked")
                    .build();
        }

        // Step 3: Build successful validation response
        // Extract expiry from claims and convert from java.util.Date to LocalDateTime
        LocalDateTime expiresAt = claims.getExpiration()
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        // Extract roles — stored as List<String> in JWT claims
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);

        return TokenValidationResponse.builder()
                .valid(true)
                .userId(java.util.UUID.fromString(claims.getSubject()))
                .deviceId(claims.get("deviceId", String.class))
                .roles(roles != null ? roles : List.of("ROLE_USER"))
                .expiresAt(expiresAt)
                .build();
    }



    // ── Private helpers ─────────────────────────────────────────────────

    // Normalize to E.164: +91XXXXXXXXXX
    // Algorithm: String manipulation — O(n) string length
    private String normalizeMobile(String mobile) {
        String digits = mobile.replaceAll("[^0-9]", "");  // Strip non-digits
        if (digits.length() == 10) return "+91" + digits;  // Add country code
        if (digits.length() == 12 && digits.startsWith("91")) return "+" + digits;
        if (digits.startsWith("+")) return mobile;
        throw new InvalidMobileNumberException("Cannot normalize mobile: " + mobile);
    }

    private String hashDeviceFingerprint(String fingerprint) {
        return jwtTokenProvider.hashToken(fingerprint); // Reuse SHA-256 utility
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void recordLoginAttempt(String mobile, String deviceId,
                                    boolean success, String reason) {

        LoginAttempt attempt = new LoginAttempt();
        attempt.setMobileNumber(normalizeMobile(mobile));
        attempt.setDeviceId(deviceId);
        attempt.setSuccess(success);
        attempt.setFailureReason(reason);
        attempt.setAttemptedAt(LocalDateTime.now());
        loginAttemptRepository.save(attempt);
    }
}


