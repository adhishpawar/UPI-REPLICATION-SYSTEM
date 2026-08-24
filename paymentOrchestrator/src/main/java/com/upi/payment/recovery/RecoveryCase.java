package com.upi.payment.recovery;

import com.upi.payment.domain.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An open investigation into one transaction whose outcome is unknown.
 *
 * <p><b>Ownership (D-001).</b> This record belongs to the recovery subsystem.
 * The transaction's <em>state</em> does not: recovery decides what should
 * happen and the Payment Orchestrator applies it through the same state
 * machine that guards every other transition. Two components writing
 * transaction state would mean no source of truth for whether money moved.
 */
@Entity
@Table(name = "recovery_cases")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RecoveryCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "case_id", updatable = false, nullable = false)
    private UUID caseId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "detected_at", nullable = false)
    @Builder.Default
    private LocalDateTime detectedAt = LocalDateTime.now();

    /**
     * The state the transaction was in when the anomaly was found.
     *
     * <p>This identifies which leg is in doubt, and therefore what question to
     * ask the money-holder. Without it, recovery would have to guess which
     * movement it is reconciling.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "detected_state", nullable = false, length = 30)
    private TransactionStatus detectedState;

    @Column(name = "classification", length = 40)
    private String classification;

    @Column(name = "strategy", length = 40)
    private String strategy;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "outcome", length = 40)
    private String outcome;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    /**
     * Lease holder, not lock holder.
     *
     * <p>An in-process lock dies with the process, leaving a case either
     * permanently unclaimable or claimable twice. A lease recorded in the
     * database expires by itself, so a crashed worker's case is picked up by
     * the next sweep. Recovery has to survive the failure of the thing doing
     * the recovering.
     */
    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    @Column(name = "claimed_until")
    private LocalDateTime claimedUntil;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
