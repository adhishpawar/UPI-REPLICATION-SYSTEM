package com.upi.payment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.payment.domain.dto.*;
import com.upi.payment.domain.entity.Transaction;
import com.upi.payment.domain.entity.TransactionEvent;
import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.exception.TransactionNotFoundException;
import com.upi.payment.exception.VpaNotFoundException;
import com.upi.payment.exception.VpaOwnershipException;
import com.upi.payment.mapper.PaymentMapper;
import com.upi.payment.observability.ExecutionRecorder;
import com.upi.payment.repository.TransactionEventRepository;
import com.upi.payment.repository.TransactionRepository;
import com.upi.payment.saga.SagaOrchestrator;
import com.upi.payment.service.PaymentService;
import com.upi.payment.util.RrnGenerator;
import com.upi.payment.vpa.VpaAccountResponse;
import com.upi.payment.vpa.VpaServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Accepts payments and answers questions about them.
 *
 * <h3>Idempotency: the constraint is the guard</h3>
 *
 * The duplicate check is <b>not</b> "look it up, and insert if absent". That
 * is a check-then-act race: two concurrent requests carrying the same key both
 * read "absent", both proceed, and the payment happens twice.
 *
 * <p>What actually works is to attempt the insert and let the UNIQUE
 * constraint on {@code idempotency_key} decide. Exactly one insert can win;
 * the loser catches the violation and reads the winner's row. The original
 * code already did this, and it is preserved -- it was the right instinct. What
 * was removed is the servlet filter that performed a *separate*, racy check
 * before it, and which read a misspelled header (`Idempotency=Key`) so that
 * every request was rejected as missing its key.
 *
 * <p>The key must be client-supplied. A server cannot distinguish a retry from
 * a genuine second payment of the same amount to the same payee; only the
 * caller knows its own intent.
 *
 * <h3>202, not 200</h3>
 *
 * Initiation returns {@code 202 Accepted}: the payment has been recorded and
 * will be processed, but has not happened yet. Returning 200 would imply the
 * money has moved. The client polls, or watches the execution stream.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private static final String COMPONENT = "payment-orchestrator";

    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository eventRepository;
    private final SagaOrchestrator sagaOrchestrator;
    private final PaymentMapper paymentMapper;
    private final RrnGenerator rrnGenerator;
    private final ObjectMapper objectMapper;
    private final VpaServiceClient vpaServiceClient;
    private final ExecutionRecorder recorder;

    @Override
    @Transactional
    public PaymentInitiationResponse initiatePayment(PaymentInitiationRequest request,
                                                     UUID userId,
                                                     String deviceId,
                                                     String idempotencyKey) {

        String traceId = MDC.get("traceId");

        log.info("Payment initiation: payer={} payee={} amount={} user={} key={}",
                request.getPayerVpa(), request.getPayeeVpa(),
                request.getAmount(), userId, idempotencyKey);

        recorder.record(traceId, null, COMPONENT, "POST /api/v1/payments",
                ExecutionRecorder.Kind.HTTP_IN, ExecutionRecorder.Status.STARTED, null,
                "payment requested: " + request.getAmount() + " "
                        + request.getPayerVpa() + " -> " + request.getPayeeVpa(),
                Map.of("idempotencyKey", idempotencyKey));

        // ── Fast path for an obvious replay ──────────────────────────────
        // An optimisation only. The constraint below is what guarantees
        // correctness; this just avoids doing work we already know is wasted.
        var replay = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return replayOf(replay.get(), traceId);
        }

        // ── Resolve both parties BEFORE anything else ────────────────────
        // Deliberately before the insert. An unresolvable VPA should never
        // produce a transaction row: rejecting it up front means it never
        // enters the lifecycle, never needs recovery, and never has to be
        // explained. Cheap validation before expensive state.
        VpaAccountResponse payer = vpaServiceClient.resolveAccount(request.getPayerVpa());
        VpaAccountResponse payee = vpaServiceClient.resolveAccount(request.getPayeeVpa());

        if (payer == null || Boolean.FALSE.equals(payer.getIsActive())) {
            throw new VpaNotFoundException(request.getPayerVpa());
        }

        // Does this VPA actually belong to the caller?
        //
        // This check was impossible before authentication was real: identity
        // arrived as an X-User-Id header the caller chose, so comparing it to
        // the VPA's owner compared a claim against a fact and would have
        // rejected nothing. Now that the user id comes from a signed token,
        // the comparison means something -- and without it, any authenticated
        // user could spend from any VPA they could name.
        if (payer.getUserId() != null && !payer.getUserId().equals(userId)) {
            log.warn("User {} attempted to pay from VPA {} owned by {}",
                    userId, request.getPayerVpa(), payer.getUserId());
            throw new VpaOwnershipException(request.getPayerVpa());
        }
        if (payee == null || Boolean.FALSE.equals(payee.getIsActive())) {
            throw new VpaNotFoundException(request.getPayeeVpa());
        }

        Transaction txn = Transaction.builder()
                .rrn(generateUniqueRrn())
                .idempotencyKey(idempotencyKey)
                .payerVpa(request.getPayerVpa().toLowerCase().trim())
                .payeeVpa(request.getPayeeVpa().toLowerCase().trim())
                .payerAccountNumber(payer.getAccountNumber())
                .payeeAccountNumber(payee.getAccountNumber())
                .payeeAccountHolderName(payee.getAccountHolderName())
                .amount(request.getAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
                .remarks(request.getRemarks())
                .currentState(TransactionStatus.INITIATED)
                .payerUserId(userId)
                .deviceId(deviceId)
                .traceId(traceId)
                .demoScenario(request.getSimulate())
                .initiatedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Transaction saved;
        try {
            saved = transactionRepository.saveAndFlush(txn);
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent requests with the same key. Exactly one insert
            // won; we are the loser, so return what the winner produced.
            log.warn("Concurrent duplicate for idempotency key {}", idempotencyKey);
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> replayOf(existing, traceId))
                    .orElseThrow(() -> ex);
        }

        eventRepository.save(TransactionEvent.builder()
                .transactionId(saved.getTransactionId())
                .fromState(TransactionStatus.INITIATED)
                .toState(TransactionStatus.INITIATED)
                .description("Payment initiated")
                .triggeredBy(COMPONENT)
                .occurredAt(LocalDateTime.now())
                .build());

        PaymentInitiationResponse response = paymentMapper.toInitiationResponse(saved);
        cacheIdempotentResponse(saved, response);

        recorder.record(traceId, saved.getTransactionId(), COMPONENT,
                "INSERT transactions", ExecutionRecorder.Kind.DB,
                ExecutionRecorder.Status.OK, null,
                "rrn=" + saved.getRrn() + " state=INITIATED", null);

        // Saga step 1 runs in this same transaction, so the payee-validation
        // state change and the outbox command commit together with the insert.
        sagaOrchestrator.validatePayee(saved.getTransactionId());

        return response;
    }

    /**
     * Replay a previous response for a duplicate request.
     *
     * <p>Returns the <em>original</em> body, byte for byte, rather than a
     * freshly-built one. A retrying client is entitled to see the same answer
     * it would have seen the first time; recomputing it could show a later
     * state and make the retry look like it did something.
     */
    private PaymentInitiationResponse replayOf(Transaction existing, String traceId) {
        log.info("Idempotent replay: key={} txn={}",
                existing.getIdempotencyKey(), existing.getTransactionId());

        recorder.record(traceId, existing.getTransactionId(), COMPONENT,
                "idempotency replay", ExecutionRecorder.Kind.DB,
                ExecutionRecorder.Status.SKIPPED, null,
                "duplicate Idempotency-Key - returning the original response, no new payment",
                null);

        if (existing.getIdempotencyResponse() != null) {
            try {
                return objectMapper.readValue(
                        existing.getIdempotencyResponse(), PaymentInitiationResponse.class);
            } catch (Exception e) {
                log.warn("Cached idempotency response unreadable for {}, rebuilding",
                        existing.getTransactionId());
            }
        }
        return paymentMapper.toInitiationResponse(existing);
    }

    private void cacheIdempotentResponse(Transaction saved, PaymentInitiationResponse response) {
        try {
            saved.setIdempotencyResponse(objectMapper.writeValueAsString(response));
            transactionRepository.save(saved);
        } catch (Exception e) {
            // Non-fatal. A duplicate would then be answered from a rebuilt
            // response rather than the cached one -- slightly less faithful,
            // but it still returns the same transaction and still moves no
            // additional money. Failing the payment over this would be a worse
            // trade.
            log.error("Could not cache idempotency response for {}: {}",
                    saved.getTransactionId(), e.toString());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransaction(UUID transactionId, UUID userId) {
        // Authorisation is in the query, not a separate check: a user can only
        // read their own payments. A guessed transaction id returns 404 rather
        // than 403, because 403 would confirm that the id exists.
        Transaction txn = transactionRepository
                .findByTransactionIdAndPayerUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        return paymentMapper.toDetailResponse(txn);
    }

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

    /**
     * Generate an RRN, retrying on collision.
     *
     * <p>Belt and braces: the UNIQUE constraint on {@code rrn} is the real
     * guarantee. This loop just avoids surfacing an error for something the
     * system can resolve itself.
     */
    private String generateUniqueRrn() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String rrn = rrnGenerator.generate();
            if (!transactionRepository.existsByRrn(rrn)) {
                return rrn;
            }
            log.warn("RRN collision on attempt {}", attempt + 1);
        }
        throw new IllegalStateException("Could not generate a unique RRN after 3 attempts");
    }

    /**
     * Terminal means the client can stop polling.
     *
     * <p>{@code UNCERTAIN} and {@code RECONCILING} are deliberately absent:
     * a payment whose outcome is unknown is not finished, and telling the
     * client otherwise would be a lie about money. {@code MANUAL_REVIEW} is
     * terminal because the system genuinely will not act further on its own.
     */
    private boolean isTerminalState(TransactionStatus state) {
        return switch (state) {
            case COMPLETED, DEBIT_FAILED, REVERSED, FAILED, MANUAL_REVIEW -> true;
            default -> false;
        };
    }
}
