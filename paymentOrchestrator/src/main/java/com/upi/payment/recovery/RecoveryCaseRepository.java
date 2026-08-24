package com.upi.payment.recovery;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecoveryCaseRepository extends JpaRepository<RecoveryCase, UUID> {

    Optional<RecoveryCase> findByTransactionId(UUID transactionId);

    boolean existsByTransactionIdAndClosedAtIsNull(UUID transactionId);

    /**
     * Claim cases whose lease has expired.
     *
     * <p>A single atomic UPDATE, not a read followed by a write. The
     * {@code claimedUntil} predicate lives in the same statement that sets the
     * new lease, so two workers racing for the same case cannot both succeed:
     * the database serialises them and the loser matches zero rows.
     *
     * <p>Doing this as SELECT-then-UPDATE would reintroduce exactly the
     * check-then-act race that idempotency keys exist to prevent -- and here
     * the consequence is two workers reconciling one payment simultaneously.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RecoveryCase c SET c.claimedBy = :worker, c.claimedUntil = :until, "
         + "c.updatedAt = :now WHERE c.closedAt IS NULL "
         + "AND (c.claimedUntil IS NULL OR c.claimedUntil < :now) AND c.caseId IN :ids")
    int claim(@Param("worker") String worker,
              @Param("until") LocalDateTime until,
              @Param("now") LocalDateTime now,
              @Param("ids") List<UUID> ids);

    @Query("SELECT c FROM RecoveryCase c WHERE c.closedAt IS NULL "
         + "AND (c.claimedUntil IS NULL OR c.claimedUntil < :now) ORDER BY c.detectedAt ASC")
    List<RecoveryCase> findClaimable(@Param("now") LocalDateTime now, Pageable pageable);

    List<RecoveryCase> findByClaimedByAndClosedAtIsNull(String worker);

    long countByClosedAtIsNull();

    List<RecoveryCase> findTop20ByOrderByDetectedAtDesc();
}
