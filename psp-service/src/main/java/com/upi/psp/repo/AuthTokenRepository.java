package com.upi.psp.repo;

// ─────────────────────────────────────────────────────────────────────────────
// ROLE: Data access layer for the 'auth_tokens' table.
//       Manages storage and retrieval of JWT token records.
//
// LOGIC:
//   - On login: a new AuthToken record is saved with the SHA-256 hash of the JWT.
//   - On logout: the record matching the token hash is marked is_revoked=true.
//   - On validate: checks if a token hash exists AND is not revoked.
//   - On cleanup: a scheduled job (future) deletes expired tokens.
//
// SECURITY DESIGN: We store SHA-256(rawJwt) not the raw JWT string itself.
//   Reason: If this table is ever read by an attacker, they cannot extract
//   valid JWTs from hashes (SHA-256 is one-way). The raw JWT is never persisted.
//
// JAVA CONCEPT: @Query with JPQL (Java Persistence Query Language) is used
//   when Spring cannot derive the query from the method name alone.
//   JPQL uses class/field names (Java), not table/column names (SQL).
// ─────────────────────────────────────────────────────────────────────────────

import com.upi.psp.domain.entity.AuthToken;
import com.upi.psp.domain.enums.TokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    /**
     * Find a non-revoked token by its SHA-256 hash.
     * Used in:
     *   - logout(): to find and revoke the token
     *   - validateToken(): to check revocation status
     * Uses partial index idx_token_hash WHERE is_revoked = FALSE — very fast.
     */
    Optional<AuthToken> findByTokenHashAndIsRevokedFalse(String tokenHash);

    /**
     * Find a non-revoked token by hash and type.
     * Used in the refresh token flow: find refresh token before issuing new access token.
     */
    Optional<AuthToken> findByTokenHashAndTokenTypeAndIsRevokedFalse(
            String tokenHash, TokenType tokenType);

    /**
     * Find all active (non-revoked) tokens for a user.
     * Used in "logout all devices" feature (future):
     *   authTokenRepository.findAllActiveByUserId(userId)
     *     .forEach(t -> t.setIsRevoked(true))
     */
    List<AuthToken> findAllByUserIdAndIsRevokedFalse(UUID userId);

    /**
     * Revoke ALL tokens for a user on a specific device.
     * Used when user reports device stolen: invalidate all tokens from that device.
     * @Modifying + @Transactional required for write operation.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE AuthToken t SET t.isRevoked = true, t.revokedAt = :now
            WHERE t.userId = :userId AND t.deviceId = :deviceId AND t.isRevoked = false
            """)
    int revokeAllTokensForDevice(@Param("userId") UUID userId,
                                 @Param("deviceId") String deviceId,
                                 @Param("now") LocalDateTime now);

    /**
     * Revoke ALL tokens for a user (logout from all devices).
     */
    @Modifying
    @Transactional
    @Query("UPDATE AuthToken t SET t.isRevoked = true, t.revokedAt = :now " +
            "WHERE t.userId = :userId AND t.isRevoked = false")
    int revokeAllTokensForUser(@Param("userId") UUID userId,
                               @Param("now") LocalDateTime now);

    /**
     * Cleanup job: delete expired and revoked tokens older than retentionDays.
     * Prevents the auth_tokens table from growing indefinitely.
     * Run daily via @Scheduled (future implementation).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM AuthToken t WHERE t.expiresAt < :cutoff AND t.isRevoked = true")
    int deleteExpiredAndRevokedTokens(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Count active tokens per user. Used for enforcing max-device limits (future).
     */
    @Query("SELECT COUNT(t) FROM AuthToken t " +
            "WHERE t.userId = :userId AND t.isRevoked = false AND t.expiresAt > :now")
    long countActiveTokensForUser(@Param("userId") UUID userId,
                                  @Param("now") LocalDateTime now);
}
