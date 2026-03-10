package com.upi.psp.repo;

import com.upi.psp.domain.entity.User;
import com.upi.psp.domain.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Find user by mobile number AND device ID.
     * Used in login: both mobile + device must match for authentication.
     * Uses UNIQUE index (mobile_number, device_id) — O(log n) B-tree lookup.
     */
    Optional<User> findByMobileNumberAndDeviceId(String mobileNumber, String deviceId);

    /*
     * Find user by mobile number alone.
     * Used in admin lookups and future OTP verification flow.
     * Returns Optional because a mobile may have multiple devices — in that
     * case use findAllByMobileNumber instead.
     */
    Optional<User> findByMobileNumber(String mobileNumber);

    /*
     * Find all active users for a given mobile number.
     * A user can register the same mobile on multiple devices (e.g. upgrade phone).
     * Returns List because multiple records may exist.
     */
    List<User> findAllByMobileNumberAndIsActive(String mobileNumber);

    /**
     * Existence check for mobile + device combo.
     * Uses SELECT EXISTS (COUNT query) — more efficient than loading the full entity.
     * Used in registerUser() to detect duplicate registrations.
     */
    boolean existsByMobileNumberAndDeviceId(String mobileNumber, String deviceId);

    /**
     * Find all users by status. Used for admin reporting and batch jobs.
     * Example: find all LOCKED users for auto-unlock after 24 hours (future feature).
     */
    List<User> findAllByStatus(UserStatus status);

    /*
     * Bulk status update — auto-unlock accounts locked > 24 hours ago.
     * @Modifying required for UPDATE/DELETE queries.
     * @Transactional required for write operations in repository layer.
     *
     * Future use: scheduled job runs this every hour.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE User u SET u.status = 'ACTIVE', u.failedLoginCount = 0
            WHERE u.status = 'LOCKED'
            AND u.lastFailedLoginAt < :cutoffTime
            """)
    int unlockAccountsLockedBefore(@Param("cutoffTime") java.time.LocalDateTime cutoffTime);


    /**
     * Count active users. Useful for monitoring dashboards.
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.isActive = true AND u.status = 'ACTIVE'")
    long countActiveUsers();


}
