package com.upi.psp.repo;



// LOGIC:
//   - Every login attempt (success or failure) creates a LoginAttempt record.
//   - Used for: security audits, fraud detection, compliance reporting.
//   - Rate limiting is NOT done via DB queries here — that's handled by
//     Bucket4j in-memory. This table is only for the audit trail.
//   - countRecentFailures() is a secondary check for account lock threshold.
//
// DESIGN NOTE: The @Repository annotation is redundant when extending
//   JpaRepository, but we include it for clarity and to enable Spring's
//   exception translation (converts SQL exceptions to Spring's DataAccessException).


import com.upi.psp.domain.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID>{

    /**
     * Count failed login attempts for a mobile number in a time window.
     * Secondary validation layer — the primary rate limiting is Bucket4j.
     * This is the source of truth for the "auto-lock after 10 failures" rule.
     *
     * Example: countRecentFailures("+911234567890", 15 minutes ago)
     */

    @Query("""
            SELECT COUNT(a) FROM LoginAttempt a 
            WHERE  a.mobileNumber = :mobile
            AND a.success  = false
            AND a.attemptedAt >= :since
            """)
    long countRecentFailures(@Param("mobile") String mobileNumber, @Param("since") LocalDateTime since);

    /**
     * Get recent attempts for a mobile number (for security dashboard).
     * Ordered by most recent first. Limited by caller to avoid loading millions of rows.
     */
    List<LoginAttempt> findTop20ByMobileNumberOrderByAttemptedArDesc(String mobileNumber);

    /**
     * Get all attempts from a specific IP address within a time window.
     * Fraud detection: if one IP tries 100 different mobile numbers → suspicious.
     */
    @Query("""
            SELECT a FROM LoginAttempt a
            WHERE a.ipAddress = :ip
            AND a.attemptedAt >= :since
            ORDER BY a.attemptedAt DESC
            """)
    List<LoginAttempt> findAttemptsByIp(@Param("ip") String ipAddress, @Param("since") LocalDateTime since);

    /**
     * Count total successful logins for a user.
     * Used in security reports: "this user has logged in X times."
     */
    @Query("SELECT COUNT(a) FROM LoginAttempt a " +
            "WHERE a.mobileNumber = :mobile AND a.success = true")
    long countSuccessfulLogins(@Param("mobile") String mobileNumber);

    /**
     * Archive cleanup: find old records to delete after retention period (90 days).
     * Used by a scheduled cleanup job (future).
     */
    @Query("SELECT a FROM LoginAttempt a WHERE a.attemptedAt < :cutoff")
    List<LoginAttempt> findAttemptsOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
