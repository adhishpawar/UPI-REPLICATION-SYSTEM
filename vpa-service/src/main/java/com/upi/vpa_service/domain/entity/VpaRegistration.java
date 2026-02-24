package com.upi.vpa_service.domain.entity;


import jakarta.persistence.*;
import lombok.*;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "vpa_registry" , indexes = {
        @Index(name = "idx_vpa_address",  columnList = "vpa_address",  unique = true),
        @Index(name = "idx_vpa_user_id",  columnList = "user_id"),
})
@EntityListeners(AuditingEntityListener.class)     //Enables @CreatedDate
@Getter
@Setter @NoArgsConstructor
public class VpaRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column
    private UUID vpaId;

    @Column(name = "vpa_address", unique = true, nullable = false, length = 100)
    private String vpaAddress;

    @Column(name = "user_id", nullable = false)
    private UUID userId;                                 // FK reference — no DB constraint (cross-service)

    @Column(name = "account_number", nullable = false)  // Stored encrypted
    private String accountNumber;

    @Column(name = "ifsc_code")
    private String ifscCode;

    @Column(name = "account_holder_name", nullable = false, length = 200)
    private String accountHolderName;

    @Column(name = "psp_handle", nullable = false, length = 50)
    private String pspHandle;              // e.g. okaxis, ybl, paytm

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;       // Soft-delete flag

    @CreatedDate                           // Auto-set by Spring on INSERT
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate                      // Auto-updated by Spring on UPDATE
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

