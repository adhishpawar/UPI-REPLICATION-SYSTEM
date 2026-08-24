package com.upi.payment.repository;

import com.upi.payment.domain.entity.Transaction;
import com.upi.payment.domain.enums.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Idempotency lookup — most critical query in the service.
     * Called by IdempotencyFilter on EVERY POST /payments request.
     * Uses UNIQUE index on idempotency_key → O(log n) B-tree lookup.
     *
     * If found → return cached response. If empty → process as new.
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Fetch a specific transaction for a specific user.
     * The payerUserId guard ensures users cannot fetch other people's payments
     * even if they guess a valid transactionId (authorization check in DB layer).
     */
    Optional<Transaction> findByTransactionIdAndPayerUserId(
            UUID transactionId, UUID payerUserId);


    /**
     * Paginated payment history for a user.
     * Used by GET /payments/history.
     * Spring Data derives ORDER BY from Pageable — caller passes
     *   PageRequest.of(page, size, Sort.by("initiatedAt").descending())
     */
    Page<Transaction> findByPayerUserId(UUID payerUserId, Pageable pageable);

    /**
     * Find all transactions in a specific state.
     * Used by scheduled timeout-detection job (future Phase 6):
     *   find all DEBIT_REQUESTED transactions older than 30 min → mark FAILED
     */
    List<Transaction> findAllByCurrentState(TransactionStatus status);


    /**
     * Find stale in-progress transactions for timeout detection.
     * Any payment stuck in a non-terminal state for > cutoffTime is considered timed out.
     *
     * JPQL note: 'NOT IN' list must use parameter, not inline values.
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.currentState NOT IN (
                com.upi.payment.domain.enums.TransactionStatus.COMPLETED,
                com.upi.payment.domain.enums.TransactionStatus.FAILED,
                com.upi.payment.domain.enums.TransactionStatus.REVERSED,
                com.upi.payment.domain.enums.TransactionStatus.DEBIT_FAILED
            )
            AND t.initiatedAt < :cutoffTime
            ORDER BY t.initiatedAt ASC
            """)
    List<Transaction> findStaleTransactions(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Count payments by state — used for monitoring dashboards and alerting.
     * Example: "alert if DEBIT_REQUESTED count > 500 for more than 5 min"
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.currentState = :state")
    long countByState(@Param("state") TransactionStatus state);


    /**
     * Check if a transaction with this RRN already exists.
     * Used in RrnGenerator to guarantee uniqueness before persisting.
     */
    boolean existsByRrn(String rrn);

    /**
     * The detector's sweep: in-flight payments whose reply is overdue.
     *
     * <p>Backed by a partial index on {@code state_deadline_at} -- completed
     * payments vastly outnumber in-flight ones and must not be scanned.
     */
    List<Transaction> findByCurrentStateInAndStateDeadlineAtBefore(
            java.util.Collection<TransactionStatus> states, LocalDateTime before);

    List<Transaction> findTop50ByOrderByInitiatedAtDesc();

    /**
     * Summary stats for a user — total paid, total received (future analytics).
     */
    @Query("""
            SELECT COUNT(t), SUM(t.amount)
            FROM Transaction t
            WHERE t.payerUserId = :userId
            AND t.currentState = com.upi.payment.domain.enums.TransactionStatus.COMPLETED
            """)
    Object[] getCompletedPaymentSummary(@Param("userId") UUID userId);
}
