package com.upi.psp.security;

import com.upi.psp.exception.InvalidTokenException;
import com.upi.psp.exception.TokenExpiredException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class JwtTokenProviderTest {

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    private final UUID TEST_USER_ID  = UUID.randomUUID();
    private final String TEST_DEVICE = "device-abc-123";

    @Test
    @DisplayName("Should generate a valid RS256 JWT")
    void generateToken_shouldReturnValidJwt() {
        String token = jwtTokenProvider.generateToken(TEST_USER_ID, TEST_DEVICE);

        assertNotNull(token);
        // JWT format: header.payload.signature — 3 parts separated by '.'
        assertEquals(3, token.split("\\.").length);
    }

    @Test
    @DisplayName("Should extract correct userId from token")
    void validateToken_shouldExtractCorrectUserId() {
        String token = jwtTokenProvider.generateToken(TEST_USER_ID, TEST_DEVICE);
        Claims claims = jwtTokenProvider.validateAndExtractClaims(token);

        assertEquals(TEST_USER_ID.toString(), claims.getSubject());
        assertEquals(TEST_DEVICE, claims.get("deviceId", String.class));
    }

    @Test
    @DisplayName("Should throw TokenExpiredException for expired token")
    void validateToken_expiredToken_throwsException() throws Exception {
        // Generate a token that expired 1 second ago
        // We need a custom token for this — use reflection to set expiry
        // Alternative: Use a test-only method that accepts custom expiry
        // For now, verify the exception type is correct

        String expiredToken = jwtTokenProvider.generateExpiredToken(TEST_USER_ID, TEST_DEVICE);
        assertThrows(TokenExpiredException.class,
                () -> jwtTokenProvider.validateAndExtractClaims(expiredToken));
    }


    @Test
    @DisplayName("Should throw InvalidTokenException for tampered token")
    void validateToken_tamperedToken_throwsException() {
        String token = jwtTokenProvider.generateToken(TEST_USER_ID, TEST_DEVICE);
        // Tamper with the signature part (last segment after final '.')
        String[] parts = token.split("\\.");
        String tamperedToken = parts[0] + "." + parts[1] + ".INVALIDSIG123";

        assertThrows(InvalidTokenException.class,
                () -> jwtTokenProvider.validateAndExtractClaims(tamperedToken));
    }

    @Test
    @DisplayName("SHA-256 hash should be deterministic")
    void hashToken_sameinput_sameHash() {
        String token = "test-token-value";
        String hash1 = jwtTokenProvider.hashToken(token);
        String hash2 = jwtTokenProvider.hashToken(token);

        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());  // SHA-256 produces 64 hex chars
    }

}
