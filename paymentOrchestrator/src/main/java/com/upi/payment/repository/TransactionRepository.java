package com.upi.payment.repository;

import com.upi.payment.domain.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
