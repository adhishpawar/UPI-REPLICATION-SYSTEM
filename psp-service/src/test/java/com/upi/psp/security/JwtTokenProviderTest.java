package com.upi.psp.security;

import com.upi.psp.exception.InvalidTokenException;
import com.upi.psp.exception.TokenExpiredException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.security.interfaces.RSAPrivateKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class JwtTokenProviderTest {

    // Was declared as SecurityConfig, which has none of these methods, so this
    // test class had never compiled -- and because `spring-boot:run` runs
    // test-compile first, it also prevented the service from starting at all.
    // A test that does not compile is worse than no test: it looks like
    // coverage in the file listing and provides none.
    @Autowired
    JwtTokenProvider jwtTokenProvider;

    /**
     * The same signing key the provider uses, so the test can mint a token
     * that is genuinely well-formed and genuinely expired.
     */
    @Autowired
    RSAPrivateKey signingKey;

    @Value("${jwt.issuer:upi-psp-service}")
    String issuer;

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
    void validateToken_expiredToken_throwsException() {
        // The token is minted here rather than by a `generateExpiredToken`
        // method on the provider. A security component should not carry a
        // method whose only purpose is to produce invalid credentials -- it is
        // a backdoor that exists in production to satisfy a test, and the next
        // person to read it has to work out whether anything calls it.
        //
        // Signing with the real key means this exercises the true expiry path
        // rather than a special case the provider knows about.
        String expiredToken = expiredTokenFor(TEST_USER_ID, TEST_DEVICE);
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

    /** A well-formed, correctly-signed token whose expiry is in the past. */
    private String expiredTokenFor(UUID userId, String deviceId) {
        Date issuedAt = new Date(System.currentTimeMillis() - 7200_000L);  // 2h ago
        Date expiry   = new Date(System.currentTimeMillis() - 3600_000L);  // 1h ago
        return Jwts.builder()
                .setIssuer(issuer)
                .setSubject(userId.toString())
                .setIssuedAt(issuedAt)
                .setExpiration(expiry)
                .claim("deviceId", deviceId)
                .claim("roles", List.of("ROLE_USER"))
                .signWith(signingKey, SignatureAlgorithm.RS256)
                .compact();
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
