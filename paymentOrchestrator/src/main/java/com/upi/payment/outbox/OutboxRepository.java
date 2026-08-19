package com.upi.payment.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Claim a batch of undelivered messages.
     *
     * <p>{@code PESSIMISTIC_WRITE} plus {@code SKIP LOCKED} is the standard
     * queue-in-a-table pattern. Without {@code SKIP LOCKED}, a second relay
     * instance blocks behind the first on the same rows and the two run
     * serially. With it, each instance takes rows nobody else holds and they
     * scale out cleanly.
     *
     * <p>Ordering is by {@code createdAt} so events for a payment are
     * delivered in the order they were produced. That matters: a
     * {@code CreditRequested} arriving before the {@code DebitRequested} that
     * preceded it would be nonsense.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
           SELECT m FROM OutboxMessage m
           WHERE m.publishedAt IS NULL
             AND m.deadLettered = false
             AND m.nextAttemptAt <= :now
           ORDER BY m.createdAt ASC
           """)
    List<OutboxMessage> claimBatch(@Param("now") LocalDateTime now, Pageable pageable);

    long countByPublishedAtIsNullAndDeadLetteredFalse();

    List<OutboxMessage> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    List<OutboxMessage> findByDeadLetteredTrueOrderByCreatedAtDesc(Pageable pageable);
}
