package com.upi.payment.repository;

/**
 * ROLE: Data access layer for the 'transaction_events' table — the append-only audit log.
 *
 * LOGIC:
 *   Every state transition creates a new row here via SagaOrchestrator.appendEvent().
 *   This repository only ever does INSERTS — never UPDATE or DELETE.
 *   The @PreUpdate guard on TransactionEvent entity enforces this at the Java level.
 *
 * WHY SEPARATE TABLE (not just a column on transactions):
 *   A payment can go through up to 7 state transitions (happy path).
 *   Storing all events in one row would require 14 nullable columns (from+to×7).
 *   A separate event table is normalized, flexible, and query-friendly.
 *   It also makes the audit trail queryable: "show me all CREDIT_FAILED events today"
 *
 * FINANCIAL COMPLIANCE NOTE:
 *   This table is your audit log for RBI (Reserve Bank of India) compliance.
 *   Rows must never be deleted or modified.
 *   Add database-level INSERT-only role in production environments.
 */

import com.upi.payment.domain.entity.TransactionEvent;
import com.upi.payment.domain.enums.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionEventRepository extends JpaRepository<TransactionEvent, UUID> {

    /**
     * Fetch all events for a transaction, ordered chronologically.
     * Used by GET /payments/{id} to build the full audit trail.
     * One SQL query returns the complete state history.
     */
    List<TransactionEvent> findByTransactionIdOrderByOccurredAtAsc(UUID transactionId);

    /**
     * Count events for a transaction — sanity check during testing.
     * Happy path should produce exactly 5 events. Reversal path: 7 events.
     */
    long countByTransactionId(UUID transactionId);

    /**
     * Find all events of a specific type across all transactions.
     * Used for operations monitoring: "how many CREDIT_FAILED events in last hour?"
     * Powers future alerting: alert if CREDIT_FAILED count > threshold.
     */
    @Query("""
            SELECT e FROM TransactionEvent e
            WHERE e.toState = :state
            AND e.occurredAt >= :since
            ORDER BY e.occurredAt DESC
            """)
    List<TransactionEvent> findByToStateAndOccurredAtAfter(
            @Param("state") TransactionStatus state,
            @Param("since") LocalDateTime since);

    /**
     * Get the most recent event for a transaction.
     * Used as a quick check: "what was the last thing that happened to this payment?"
     */
    @Query("""
            SELECT e FROM TransactionEvent e
            WHERE e.transactionId = :transactionId
            ORDER BY e.occurredAt DESC
            LIMIT 1
            """)
    java.util.Optional<TransactionEvent> findLatestEventForTransaction(
            @Param("transactionId") UUID transactionId);

    /**
     * Find reversal events for reporting.
     * Answers: "how many payments were reversed today due to credit failure?"
     */
    @Query("""
            SELECT COUNT(e) FROM TransactionEvent e
            WHERE e.toState = com.upi.payment.domain.enums.TransactionStatus.REVERSAL_INITIATED
            AND e.occurredAt >= :since
            """)
    long countReversalsInitiatedSince(@Param("since") LocalDateTime since);
}