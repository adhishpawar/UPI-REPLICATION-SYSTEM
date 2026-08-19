package com.upi.payment.domain.entity;


import com.upi.payment.domain.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions",
        indexes = {
                @Index(name="idx_txn_payer_user", columnList="payer_user_id,initiated_at DESC"),
                @Index(name="idx_txn_state",      columnList="current_state"),
        })
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor
@Builder
@AllArgsConstructor
public class Transaction{

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name="transactionId", updatable = false, nullable = false)
    private UUID transactionId;

    @Column(name = "rrn", unique = true, nullable = false, length = 30)
    private String rrn; // Bank Reference Number — generated at initiation

    @Column(name = "idempotency key", unique = true, nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "idempotency_response", columnDefinition = "TEXT")
    private String idempotencyResponse; //cached json for retry responses

    @Column(name = "payer_vpa", nullable = false, length = 100)
    private String payerVpa;

    @Column(name = "payee_vpa", nullable = false, length = 100)
    private String payeeVpa;

    @Column(name = "payee_account_holder_name", length = 200)
    private String payeeAccountHolderName;

    //IMP --> BigDecimal for Money --> NEVER float or double
    @Column(name="amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name="currency", nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name="current_state", nullable = false, length = 30)
    private TransactionStatus currentState;

    @Column(name = "payer_user_id", nullable = false)
    private UUID payerUserId;

    @Column(name="device_id", nullable=false, length=255)
    private String deviceId;

    @Column(name="remarks", length=500)
    private String remarks;

    @Column(name="bank_debit_reference", length=100)
    private String bankDebitReferenceNumber;

    @Column(name="bank_credit_reference", length=100)
    private String bankCreditReferenceNumber;

    @Column(name="failure_reason", length=500)
    private String failureReason;

    @Column(name="initiated_at", updatable=false, nullable=false)
    private LocalDateTime initiatedAt = LocalDateTime.now();

    @Column(name="completed_at")
    private LocalDateTime completedAt;

    @LastModifiedDate
    @Column(name="updated_at", nullable=false)
    private LocalDateTime updatedAt;

}
