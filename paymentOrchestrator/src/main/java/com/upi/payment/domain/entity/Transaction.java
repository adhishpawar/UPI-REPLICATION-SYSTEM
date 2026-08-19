package com.upi.payment.domain.entity;

import com.upi.payment.domain.enums.FundingSource;
import com.upi.payment.domain.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The payment record. Maps to {@code transactions} (Flyway V1).
 *
 * <p>Two properties of this class carry most of the correctness weight:
 *
 * <ul>
 *   <li>{@code amount} is {@link BigDecimal}, never {@code double}. Binary
 *       floating point cannot represent 0.10 exactly. A payment system built
 *       on it is out by a paisa eventually, with no way to explain where the
 *       paisa went.</li>
 *   <li>{@code version} enables optimistic locking. Two writers can
 *       legitimately target this row: a saga step reacting to a bank reply,
 *       and the orchestrator applying a recovery decision. Both may fire at
 *       nearly the same moment for a transaction that timed out and then got
 *       a late answer. Without {@code @Version} the later write silently
 *       overwrites the earlier one and the audit trail lies.</li>
 * </ul>
 */
@Entity
@Table(name = "transactions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "transaction_id", updatable = false, nullable = false)
    private UUID transactionId;

    /** Retrieval Reference Number: the receipt number a human quotes to support. */
    @Column(name = "rrn", unique = true, nullable = false, length = 12)
    private String rrn;

    /**
     * Client-supplied duplicate guard. The UNIQUE constraint on this column
     * IS the guard -- a read-then-write check is a race, because two
     * concurrent requests both read "absent" and both insert.
     *
     * <p>It has to be client-supplied: the server cannot distinguish a retry
     * from a genuine second payment of the same amount to the same payee.
     * Only the caller knows its own intent.
     */
    @Column(name = "idempotency_key", unique = true, nullable = false, length = 100)
    private String idempotencyKey;

    /** The exact body returned the first time, replayed verbatim on a duplicate. */
    @Column(name = "idempotency_response", columnDefinition = "TEXT")
    private String idempotencyResponse;

    @Column(name = "payer_vpa", nullable = false, length = 100)
    private String payerVpa;

    @Column(name = "payee_vpa", nullable = false, length = 100)
    private String payeeVpa;

    @Column(name = "payee_account_holder_name", length = 200)
    private String payeeAccountHolderName;

    /**
     * Snapshotted at payee-validation time. A VPA can later be re-pointed at
     * a different account; this payment must stay explainable years from now,
     * so we record where the money actually went, not where the VPA points.
     */
    @Column(name = "payer_account_number", length = 64)
    private String payerAccountNumber;

    @Column(name = "payee_account_number", length = 64)
    private String payeeAccountNumber;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    /** Which money-holder funds this payment. See D-003 -- the wallet is one of these. */
    @Enumerated(EnumType.STRING)
    @Column(name = "funding_source", nullable = false, length = 20)
    @Builder.Default
    private FundingSource fundingSource = FundingSource.BANK;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", nullable = false, length = 30)
    private TransactionStatus currentState;

    /** Optimistic lock. See the class comment. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "payer_user_id", nullable = false)
    private UUID payerUserId;

    @Column(name = "device_id", length = 255)
    private String deviceId;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /** Correlates every execution event, log line and outbox message for this payment. */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "bank_debit_reference", length = 100)
    private String bankDebitReferenceNumber;

    @Column(name = "bank_credit_reference", length = 100)
    private String bankCreditReferenceNumber;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * When the current in-flight state stops being plausible.
     *
     * <p>This is not a failure timer. Breaching it means the outcome has
     * become <em>unknown</em>, which is a different and far more dangerous
     * condition than failure -- see {@link TransactionStatus}. NULL once the
     * transaction is terminal.
     */
    @Column(name = "state_deadline_at")
    private LocalDateTime stateDeadlineAt;

    @Column(name = "uncertain_since")
    private LocalDateTime uncertainSince;

    /**
     * Demo only. Names a deterministic failure to inject, so the failure and
     * recovery paths can be demonstrated on demand. Never set by normal
     * traffic, never random. See {@code docs/testing/failure-scenarios.md}.
     */
    @Column(name = "demo_scenario", length = 40)
    private String demoScenario;

    @Column(name = "initiated_at", updatable = false, nullable = false)
    @Builder.Default
    private LocalDateTime initiatedAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
