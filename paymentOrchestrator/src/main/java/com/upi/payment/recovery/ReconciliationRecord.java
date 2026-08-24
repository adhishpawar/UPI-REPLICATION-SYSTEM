package com.upi.payment.recovery;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What we believed, what the money-holder actually recorded, and whether the
 * two agreed.
 *
 * <p>Kept even when they match. A reconciliation that found no discrepancy is
 * still evidence that the check ran, and "we verified this and it was fine" is
 * exactly what an auditor asks for. Storing only mismatches would make a
 * system that never ran its checks indistinguishable from one that ran them
 * and found nothing wrong.
 */
@Entity
@Table(name = "reconciliation_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReconciliationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reconciliation_id", updatable = false, nullable = false)
    private UUID reconciliationId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "leg", nullable = false, length = 20)
    private String leg;

    /** What the orchestrator thought was true before asking. */
    @Column(name = "our_belief", nullable = false, length = 40)
    private String ourBelief;

    /** What the money-holder says it recorded. The authority on this question. */
    @Column(name = "their_record", nullable = false, length = 40)
    private String theirRecord;

    @Column(name = "matched", nullable = false)
    private Boolean matched;

    @Column(name = "raw_response", columnDefinition = "JSONB")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String rawResponse;

    @Column(name = "checked_at", nullable = false)
    @Builder.Default
    private LocalDateTime checkedAt = LocalDateTime.now();
}
