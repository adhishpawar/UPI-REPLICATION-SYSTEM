package com.upi.psp.domain.entity;

import com.upi.psp.domain.enums.UserStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users",
        indexes = {
                @Index(name = "idx_users_mobile", columnList = "mobile_number"),
        })
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id",updatable = false, nullable = false)
    private UUID userId;

    @Column(name = "mobile_number", nullable = false, length = 15)
    private String mobileNumber;   // Always stored in E.164: +91XXXXXXXXXX

    @Column(name = "device_id", nullable = false, length = 255)
    private String deviceId;

    @Column(name = "device_fingerprint", nullable = false, length = 512)
    private String deviceFingerprint;

    @Column(name = "mpin_hash, length = 60")
    private String mpinHash;   // BCrypt hash. NULL until setupMpin called.  --> IMP: This field NEVER appears in any DTO or log statement

    @Enumerated(EnumType.STRING)   //Str --> DB readable
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.PENDING_MPIN;

    @Column(name = "failed_login_count", nullable = false)
    private Integer failedLoginCount = 0;

    @Column(name = "last_failed_login_at")
    private LocalDateTime lastFailedLoginAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreatedDate
    @Column(name ="created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

}
