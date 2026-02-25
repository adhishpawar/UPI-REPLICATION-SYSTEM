package com.upi.psp.security;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.upi.psp.exception.InvalidTokenException;
import com.upi.psp.exception.TokenExpiredException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
@Slf4j
public class JwtTokenProvider  {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    @Value("${jwt.issuer:upi-psp-service}")
    private String issuer;

    @Value("${jwt.access-token-expiry-seconds:3600}")
    private long accessTokenExpirySeconds;

    public JwtTokenProvider(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey  = publicKey;
    }

    /**
     * Generate a signed JWT for the given user and device.
     * Claims embedded: userId, deviceId, roles, iat, exp, iss
     */
    public String generateToken(UUID userId, String deviceId) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirySeconds * 1000L);

        return Jwts.builder()
                .setIssuer(issuer)
                .setSubject(userId.toString())        // 'sub' claim = userId
                .setIssuedAt(now)                     // 'iat' claim
                .setExpiration(expiry)                // 'exp' claim
                .claim("deviceId", deviceId)          // Custom claim
                .claim("roles", List.of("ROLE_USER")) // For future RBAC
                // RS256: Sign with RSA-2048 private key
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();   // Returns the serialized JWT string
    }

    public String generateExpiredToken(UUID userId, String deviceId) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() - accessTokenExpirySeconds * 1000L);

        return Jwts.builder()
                .setIssuer(issuer)
                .setSubject(userId.toString())        // 'sub' claim = userId
                .setIssuedAt(now)                     // 'iat' claim
                .setExpiration(expiry)                // 'exp' claim
                .claim("deviceId", deviceId)          // Custom claim
                .claim("roles", List.of("ROLE_USER")) // For future RBAC
                // RS256: Sign with RSA-2048 private key
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();   // Returns the serialized JWT string
    }

    /**
     * Validate a JWT and extract its claims.
     * Throws JwtException subtypes on any failure.
     * This is PURE CPU — no DB call, no network call.
     */
    public Claims validateAndExtractClaims(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(publicKey)          // Verify with RSA public key
                    .requireIssuer(issuer)             // Verify 'iss' claim
                    .build()
                    .parseClaimsJws(token)             // Throws if invalid/expired
                    .getBody();                        // Returns the claims payload
        } catch (ExpiredJwtException ex) {
            log.warn("JWT expired: {}", ex.getMessage());
            throw new TokenExpiredException("JWT token has expired");
        } catch (JwtException ex) {
            log.warn("JWT validation failed: {}", ex.getMessage());
            throw new InvalidTokenException("JWT token is invalid");
        }
    }

    /**
     * Extract userId from JWT without full validation.
     * Used when you just need the subject claim (e.g. for logging).
     */
    public String extractUserId(String token) {
        return validateAndExtractClaims(token).getSubject();
    }

    /**
     * Generate SHA-256 hash of the raw JWT string.
     * This hash is stored in auth_tokens table — not the JWT itself.
     */
    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            // Convert bytes to hex string
            // Java streams: IntStream over byte array → hex chars → joined
            return IntStream.range(0, hash.length)
                    .mapToObj(i -> String.format("%02x", hash[i] & 0xff))
                    .collect(Collectors.joining());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}




