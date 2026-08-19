package com.upi.payment.service.impl;

import com.upi.payment.service.PaymentService;
/**
 * ROLE: Core business logic layer for payment operations.
 *       Orchestrates the initiation flow, delegates async steps to SagaOrchestrator,
 *       and serves query endpoints (get by ID, status poll, history).
 *
 * LOGIC FLOW for initiatePayment():
 *   1. Generate RRN (unique bank reference number)
 *   2. Build Transaction entity in INITIATED state
 *   3. Save to DB — UNIQUE constraint on idempotency_key prevents duplicates
 *   4. Store idempotency response body on the entity for future retries
 *   5. Trigger Saga step 1 (validatePayee) — which calls VPA Service
 *   6. Return 202 Accepted to caller immediately (async from here)
 *
 * OUTBOX PATTERN (simplified):
 *   DB save THEN Saga/Kafka publish — never the reverse.
 *   If service crashes after DB save but before Kafka publish:
 *     - Timeout job (future) re-triggers the Saga step
 *   If service crashes after Kafka publish but before DB save:
 *     - @Transactional rolls back the DB save → consistent state
 *
 * AUTHORIZATION:
 *   getTransaction() and getStatus() include payerUserId in the DB query.
 *   This means a user can ONLY fetch their own transactions.
 *   Even if an attacker guesses a valid transactionId, the DB returns empty
 *   because their userId doesn't match → 404 Not Found (not 403 Forbidden —
 *   we don't reveal whether the transaction exists for other users).
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.payment.domain.dto.*;
import com.upi.payment.domain.entity.Transaction;
import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.exception.DuplicatePaymentException;
import com.upi.payment.exception.TransactionNotFoundException;
import com.upi.payment.mapper.PaymentMapper;
import com.upi.payment.repository.TransactionRepository;
import com.upi.payment.saga.SagaOrchestrator;
import com.upi.payment.util.RrnGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final TransactionRepository transactionRepository;
    private final SagaOrchestrator sagaOrchestrator;
    private final PaymentMapper paymentMapper;
    private final RrnGenerator rrnGenerator;
    private final ObjectMapper objectMapper;  //Jackson --> for idempotency response caching


    @Override
    @Transactional
    public PaymentInitiationResponse initiatePayment(
            PaymentInitiationRequest request,
            UUID userId,
            String deviceId,
            String idempotencyKey) {

        log.info("Initiating payment: payer={} payee={} amount={} userId={}");

        // STEP 1: Generate a unique RRN (Bank Reference Number)
        // RrnGenerator uses SecureRandom + timestamp for guaranteed uniquenes
        String rrn = generateUniqueRrn();

        // STEP 2: Build the Transaction entity in INITIATED state
        Transaction txn = Transaction.builder()
                .rrn(rrn)
                .idempotencyKey(idempotencyKey)
                .payerVpa(request.getPayerVpa().toLowerCase().trim())
                .payeeVpa(request.getPayeeVpa().toLowerCase().trim())
                .amount(request.getAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
                .remarks(request.getRemarks())
                .currentState(TransactionStatus.INITIATED)
                .payerUserId(userId)
                .deviceId(deviceId)
                .build();

        // STEP 3: Save to DB
        // If idempotencyKey already exists: DataIntegrityViolationException from UNIQUE constraint
        // This is the atomic guard — even under concurrent duplicate requests,
        // only ONE insert succeeds. The loser gets the exception, which we convert to
        // a duplicate payment error (the IdempotencyFilter catches it before this normally).

        Transaction saved;
        try {
            saved = transactionRepository.save(txn);
        } catch (DataIntegrityViolationException ex) {
            // Race condition: two concurrent requests with same idempotencyKey
            // The filter missed it (e.g., running 2 instances) — DB constraint catches it
            log.warn("Duplicate payment intercepted at DB level for key: {}", idempotencyKey);
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> paymentMapper.toInitiationResponse(existing))
                    .orElseThrow(() -> new DuplicatePaymentException(idempotencyKey));
        }

        // STEP 4: Build response
        PaymentInitiationResponse response = paymentMapper.toInitiationResponse(saved);

        // STEP 5: Cache the response JSON on the entity for idempotency replays
        // Subsequent requests with same idempotencyKey return this cached JSON
        try {
            saved.setIdempotencyResponse(objectMapper.writeValueAsString(response));
            transactionRepository.save(saved);
        } catch (JsonProcessingException e) {
            log.error("Failed to cache idempotency response for txn: {}", saved.getTransactionId());
            // Non-fatal — payment continues. Idempotency may not work perfectly for retries.
        }

        // STEP 6: Trigger Saga step 1 asynchronously
        // validatePayee() calls VPA Service and then publishes Kafka debit event
        // This is called after the transaction is committed to DB
        // If validatePayee fails, the transaction state machine handles it gracefully
        try {
            sagaOrchestrator.validatePayee(saved.getTransactionId());
        } catch (Exception ex) {
            // Saga failure is handled inside SagaOrchestrator — it updates state to FAILED
            // We still return 202 to the caller — they track via transactionId
            log.error("Saga start error for txn {}: {}", saved.getTransactionId(), ex.getMessage());
        }

        log.info("Payment initiated successfully. txnId={} rrn={}", saved.getTransactionId(), rrn);
        return response;
    }

    // ── 2. Get Full Transaction Detail ───────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransaction(UUID transactionId, UUID userId) {
        // Authorization built into the query: only the payer can fetch their transaction
        Transaction txn = transactionRepository
                .findByTransactionIdAndPayerUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        return paymentMapper.toDetailResponse(txn);
    }

    // ── 3. Lightweight Status Poll ───────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public TransactionStatusResponse getStatus(UUID transactionId, UUID userId) {
        Transaction txn = transactionRepository
                .findByTransactionIdAndPayerUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        return TransactionStatusResponse.builder()
                .transactionId(txn.getTransactionId())
                .currentState(txn.getCurrentState().name())
                .lastUpdated(txn.getUpdatedAt())
                .isTerminal(isTerminalState(txn.getCurrentState()))
                .build();
    }
    @Override
    @Transactional(readOnly = true)
    public Page<PaymentSummaryResponse> getHistory(UUID userId, Pageable pageable) {
        return transactionRepository.findByPayerUserId(userId, pageable)
                .map(paymentMapper::toSummaryResponse);
    }

    // ── 4. Paginated Payment History ─────────────────────────────────────────

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Generate a unique RRN with collision check.
     * Algorithm: generate → check DB → retry if exists (extremely rare).
     * Max 3 retries — if all collide (astronomically unlikely), throw.
     */
    private String generateUniqueRrn() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String rrn = rrnGenerator.generate();
            if (!transactionRepository.existsByRrn(rrn)) {
                return rrn;
            }
            log.warn("RRN collision on attempt {}: {}", attempt + 1, rrn);
        }
        throw new RuntimeException("Failed to generate unique RRN after 3 attempts");
    }

    /**
     * Check if a state is terminal (payment is done — success or failure).
     * Used in the status response so the mobile app knows when to stop polling.
     */
    private boolean isTerminalState(TransactionStatus state) {
        return switch (state) {
            case COMPLETED, DEBIT_FAILED, REVERSED, FAILED -> true;
            default -> false;
        };
    }
}
