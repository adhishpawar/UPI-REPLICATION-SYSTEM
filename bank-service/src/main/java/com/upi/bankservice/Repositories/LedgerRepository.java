package com.upi.bankservice.Repositories;

import com.upi.bankservice.models.Ledger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerRepository extends JpaRepository<Ledger, UUID> {

    /** Legacy lookup, kept for the original debit/credit endpoints. */
    Optional<Ledger> findByTxId(String txId);

    /**
     * The idempotency lookup at the correct grain.
     *
     * <p>One transaction has at most one debit, one credit and one reversal.
     * Keying on {@code txId} alone -- as the original code did -- makes a
     * debit and a credit for the same payment collide.
     */
    Optional<Ledger> findByTxIdAndType(String txId, Ledger.TransactionType type);

    List<Ledger> findByTxIdOrderByCreatedAtAsc(String txId);

    /**
     * Balance implied by the append-only ledger.
     *
     * <p>Only SUCCESS rows count: a FAILED posting records that a movement was
     * refused, which is worth auditing but moved no money. REVERSAL is
     * additive because it is a compensating credit.
     */
    @Query("""
           SELECT COALESCE(SUM(
               CASE
                   WHEN l.type = com.upi.bankservice.models.Ledger$TransactionType.DEBIT
                        THEN -l.amount
                   ELSE l.amount
               END), 0)
           FROM Ledger l
           WHERE l.accountNumber = :accountNumber
             AND l.status = com.upi.bankservice.models.Ledger$TransactionStatus.SUCCESS
           """)
    BigDecimal derivedBalance(@Param("accountNumber") String accountNumber);
}
