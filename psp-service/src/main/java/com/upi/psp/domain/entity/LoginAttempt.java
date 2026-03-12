package com.upi.psp.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "login_attempts",
        indexes = {
                @Index(name = "idx_attempts_mobile",
                        columnList = "mobile_number, attempted_at DESC"),
                @Index(name = "idx_attempts_ip",
                        columnList = "ip_address, attempted_at DESC")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "attempt_id", updatable = false, nullable = false)
    private UUID attemptId;

    /** Mobile number of the login attempt (normalized E.164 format). */
    @Column(name = "mobile_number", nullable = false, length = 15)
    private String mobileNumber;

    /** Device ID submitted in the login request. */
    @Column(name = "device_id", nullable = false, length = 255)
    private String deviceId;

    /*
     * IP address of the client. Extracted from HttpServletRequest.
     * Supports IPv4 (15 chars) and IPv6 (45 chars).
     * May be null if extracted from internal service calls.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;


    @Column(name = "success", nullable = false)
    private Boolean success;

    /**
     * Human-readable reason for failure. Null on success.
     * Values: INVALID_MPIN, USER_NOT_FOUND, RATE_LIMITED,
     *         ACCOUNT_LOCKED, MPIN_NOT_SET
     */
    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    /**
     * Exact timestamp of the attempt. Setting manually in service layer
     * (not via @CreatedDate) because this entity doesn't use JPA auditing.
     */
    @Column(name = "attempted_at", nullable = false, updatable = false)
    private LocalDateTime attemptedAt;

    /**
     * Convenience constructor used in AuthServiceImpl.recordLoginAttempt().
     */
    public LoginAttempt(String mobileNumber, String deviceId,
                        String ipAddress, boolean success, String failureReason) {
        this.mobileNumber  = mobileNumber;
        this.deviceId      = deviceId;
        this.ipAddress     = ipAddress;
        this.success       = success;
        this.failureReason = failureReason;
        this.attemptedAt   = LocalDateTime.now();
    }
}
