package com.upi.psp.service.impl;

import com.upi.psp.domain.dto.*;
import com.upi.psp.domain.entity.AuthToken;
import com.upi.psp.domain.entity.LoginAttempt;
import com.upi.psp.domain.entity.User;
import com.upi.psp.domain.enums.UserStatus;
import com.upi.psp.mapper.AuthMapper;
import com.upi.psp.ratelimiter.LoginRateLimiter;
import com.upi.psp.repo.AuthTokenRepository;
import com.upi.psp.repo.LoginAttemptRepository;
import com.upi.psp.repo.UserRepository;
import com.upi.psp.security.JwtTokenProvider;
import com.upi.psp.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.InvalidIsolationLevelException;
import org.springframework.transaction.annotation.Transactional;

import javax.security.auth.login.AccountLockedException;
import java.time.LocalDateTime;


@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginRateLimiter rateLimiter;
    private final AuthMapper authMapper;

    @Override
    @Transactional
    public UserRegistrationResponse registerUser(UserRegistrationRequest request) {

        //Normalize the mobile number
        String normalizedMobile = normalizeMobile(request.getMobileNumber());

        String deviceFpHash = hashDeviceFingerprint(request.getDeviceFingerprint());

        if(userRepository.existsByMobileNumberAndDeviceId(
                normalizedMobile, request.getDeviceId()
        )){
            throw new UserAlreadyExistsException(
                    "Device already registered for this Mobile Number"
            );
        }
        User user = new User();
        user.setMobileNumber(normalizedMobile);
        user.setDeviceId(request.getDeviceId());
        user.setDeviceFingerprint(deviceFpHash);
        user.setStatus(UserStatus.PENDING_MPIN);
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
    public LoginResponse login(LoginRequest request) throws AccountLockedException {
        String normalizedMobile = normalizeMobile(request.getMobileNumber());

        //S1 rate Limit check --> o(1) lookup Bucket4j
        if(!rateLimiter.tryConsume(normalizedMobile))
        {
            //Record failed attempt for audit
            recordLoginAttempt(normalizedMobile, request.getDeviceId(), false, "RATE_LIMITED");
            throw new TooManyLoginAttemptException(
                    "Too Many login Attempts. Try again in 15 minutes"
            );
        }

        //S2 Find User
        User user = userRepository.findByMobileNumberAndDeviceId(normalizedMobile, request.getDeviceId())
                .orElseThrow(() -> {
                    recordLoginAttempt(normalizedMobile, request.getDeviceId(), false, "USER_NOT_FOUND");
                    return new InvalidCredentialsException("Invalid credentials");
                    //Generic message --> don't reveal if user exists
                });
        //S3 Check account status
        if(!user.getIsActive() || user.getStatus() == UserStatus.LOCKED){
            throw new AccountLockedException("Account is locked pr inactive");
        }

        if(user.getStatus() != UserStatus.ACTIVE)
        {
            throw new MpinNotSetException("MPIN setup not completed");
        }


        //S4  Verify the MPIN -> BCrypt.matches() is the only place MPIN is used
        //takes 300ms intentionally --> work factor 12

        if(!passwordEncoder.matches(request.getMpin(), user.getMpinHash()))
        {
            user.setFailedLoginCount(user.getFailedLoginCount() + 1);
            user.setLastFailedLoginAt(LocalDateTime.now());

            //Auto lock after 10 failures
            if(user.getFailedLoginCount() >= 10)
            {
                user.setStatus(UserStatus.LOCKED);
                log.warn("Account auto Locked: user={}", user.getUserId());
            }
            userRepository.save(user);
            recordLoginAttempt(normalizedMobile, request.getDeviceId(), false, "INVALID_MPIN");
            throw new InvalidCredentailException("Invalid credentials");
        }

        //S5 Login Successful reset Failed count
        user.setFailedLoginCount(0);
        userRepository.save(user);

        //S6 generate JWT
        String rawToken = jwtTokenProvider.generateToken(
                user.getUserId(), user.getDeviceId()
        );

        //S7 Store token record (Hash Only and now raw JWT)
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

        return LoginResponse.builder()
                .accessToken(rawToken)
                .userId(user.getUserId())
                .tokenType("Bearer")
                .expiresIn(3600L)
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


    //Private Helpers
    //Normalize Number --> String Manipulation O(n)

    private String normalizeMobile(String mobile)
    {
        String digits = mobile.replaceAll("[^0-9]", "");
        if(digits.length() == 10) return "+91" + digits;;
        if(digits.length() == 12 && digits.startsWith("91")) return "+" + digits;
        if(digits.startsWith("+")) return mobile;
        throw  new InvalidMobileNumberException("Cannot  normalize mobile: " + mobile);
    }

    private String hashDeviceFingerprint(String fingerprint)
    {
        return jwtTokenProvider.hashToken(fingerprint);
    }

    private void recordLoginAttempt(String mobile, String deviceId, boolean success, String reason)
    {
        LoginAttempt attempt = new LoginAttempt();
        attempt.setMobileNumber(mobile);
        attempt.setDeviceId(deviceId);
        attempt.setSuccess(success);
        attempt.setFailureReason(reason);
        attempt.setAttemptedAt(LocalDateTime.now());
        loginAttemptRepository.save(attempt);
    }

}
