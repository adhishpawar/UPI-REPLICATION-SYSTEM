package com.upi.payment.saga;

import com.upi.payment.domain.entity.Transaction;
import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.domain.event.*;
import com.upi.payment.exception.TransactionNotFoundException;
import com.upi.payment.exception.VpaNotFoundException;
import com.upi.payment.exception.VpaServiceUnavailableException;
import com.upi.payment.kafka.PaymentEventProducer;
import com.upi.payment.repository.TransactionRepository;
import com.upi.payment.repository.TransactionEventRepository;
import com.upi.payment.statemachine.TransactionStateMachine;
import com.upi.payment.vpa.VpaServiceClient;
import com.upi.payment.domain.entity.TransactionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.upi.payment.exception.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestrator {
    private final TransactionRepository      transactionRepository;
    private final TransactionEventRepository eventRepository;
    private final TransactionStateMachine    stateMachine;
    private final PaymentEventProducer       eventProducer;
    private final VpaServiceClient           vpaServiceClient;

    // ── Step 1: Validate Payee ─────────────────────────────────────────
    @Transactional
    public void validatePayee(UUID transactionId){
        Transaction txn = loadTransaction(transactionId);

        try{
            //HTTP call to VPA service - wrapped in CircuitBreaker
            var resolution = vpaServiceClient.resolveVpa(txn.getPayeeVpa());
            // Guard: state machine enforces valid transition
            stateMachine.transition(txn.getCurrentState(),
                    TransactionStatus.PAYEE_VALIDATED);

            txn.setCurrentState(TransactionStatus.PAYEE_VALIDATED);
            txn.setPayeeAccountHolderName(resolution.getAccountHolderName());
            transactionRepository.save(txn);

            appendEvent(txn, TransactionStatus.INITIATED,
                    TransactionStatus.PAYEE_VALIDATED, "Payee VPA resolved");

            // Outbox: DB committed, THEN publish Kafka event
            eventProducer.publishDebitRequest(txn);
            log.info("Payee validated txnId={}", transactionId);
        } catch (VpaNotFoundException ex) {
            failTransaction(txn, "PAYEE_VPA_NOT_FOUND: " + txn.getPayeeVpa());
        } catch (VpaServiceUnavailableException ex) {
            failTransaction(txn, "VPA_SERVICE_UNAVAILABLE");
        }
    }

    // ── Step 2: Handle Debit Result ────────────────────────────────────
    @Transactional
    public void handleDebitSuccess(DebitSuccessEvent event) {
        Transaction txn = loadTransaction(event.getTransactionId());

        stateMachine.transition(txn.getCurrentState(), TransactionStatus.DEBITED);
        txn.setCurrentState(TransactionStatus.DEBITED);
        txn.setBankCreditReferenceNumber(event.getBankReferenceNumber());
        transactionRepository.save(txn);

        appendEvent(txn, TransactionStatus.DEBIT_REQUESTED, TransactionStatus.DEBITED, "Bank debit confirmed: " + event.getBankReferenceNumber());

        //outbox pattern --> save first then publish
        eventProducer.publishCreditRequest(txn);
        log.info("Debit success txnId={} ref{}", event.getTransactionId(), event.getBankReferenceNumber());
    }

    @Transactional
    public void handleDebitFailure(DebitFailedEvent event)
    {
        Transaction txn = loadTransaction(event.getTransactionId());
        stateMachine.transition(txn.getCurrentState(),
                TransactionStatus.DEBIT_FAILED);

        txn.setCurrentState(TransactionStatus.DEBIT_FAILED);
        txn.setFailureReason(event.getFailureReason());
        txn.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(txn);

        appendEvent(txn, TransactionStatus.DEBIT_REQUESTED,
                TransactionStatus.DEBIT_FAILED, event.getFailureReason());

        eventProducer.publishPaymentFailed(txn);
        log.warn("Debit failed txnId={} reason={}",
                event.getTransactionId(), event.getFailureReason());
    }

    // ── Step 3: Handle Credit Result ───────────────────────────────────
    @Transactional
    public void handleCreditSuccess(CreditSuccessEvent event){
        Transaction txn = loadTransaction(event.getTransactionId());

        stateMachine.transition(txn.getCurrentState(), TransactionStatus.CREDITED);
        txn.setCurrentState(TransactionStatus.CREDITED);

        stateMachine.transition(txn.getCurrentState(), TransactionStatus.COMPLETED);
        txn.setCurrentState(TransactionStatus.COMPLETED);
        txn.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(txn);

        appendEvent(txn, TransactionStatus.CREDIT_REQUESTED,
                TransactionStatus.COMPLETED, "Payment completed successfully");

        eventProducer.publishPaymentCompleted(txn);
        log.info("Payment COMPLETED txnId={} rrn={}",
                event.getTransactionId(), txn.getRrn());
    }

    // ── COMPENSATION: Handle Credit Failure → Start Reversal ──────────
    @Transactional
    public void handleCreditFailure(CreditFailedEvent event) {
        Transaction txn = loadTransaction(event.getTransactionId());

        stateMachine.transition(txn.getCurrentState(), TransactionStatus.CREDIT_FAILED);
        txn.setCurrentState(TransactionStatus.CREDIT_FAILED);
        txn.setFailureReason("Credit failed: " + event.getFailureReason());
        transactionRepository.save(txn);

        appendEvent(txn, TransactionStatus.CREDIT_REQUESTED,
                TransactionStatus.CREDIT_FAILED, event.getFailureReason());


        // SAGA COMPENSATION: Credit failed after debit → must reverse debit
        stateMachine.transition(txn.getCurrentState(), TransactionStatus.REVERSAL_INITIATED);
        txn.setCurrentState(TransactionStatus.REVERSAL_INITIATED);
        transactionRepository.save(txn);

        appendEvent(txn, TransactionStatus.CREDIT_FAILED,
                TransactionStatus.REVERSAL_INITIATED, "Starting debit reversal");

        // Publish reversal request — Bank Debit Adapter will undo the debit
        eventProducer.publishReversalRequest(txn);
        log.warn("REVERSAL initiated txnId={}", event.getTransactionId());
    }

    @Transactional
    public void handleReversalSuccess(ReversalSuccessEvent event) {
        Transaction txn = loadTransaction(event.getTransactionId());

        stateMachine.transition(txn.getCurrentState(), TransactionStatus.REVERSED);
        txn.setCurrentState(TransactionStatus.REVERSED);
        txn.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(txn);

        appendEvent(txn, TransactionStatus.REVERSAL_INITIATED,
                TransactionStatus.REVERSED, "Debit reversed successfully");

        eventProducer.publishPaymentReversed(txn);
    }

    // ── Private helpers ──────────────────────────────────────────────

    private Transaction loadTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
            .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    private void appendEvent(Transaction txn, TransactionStatus from,
                             TransactionStatus to, String description) {
        TransactionEvent event = TransactionEvent.builder()
                .transactionId(txn.getTransactionId())
                .fromState(from)
                .toState(to)
                .description(description)
                .occurredAt(LocalDateTime.now())
                .build();
        eventRepository.save(event);
    }

    private void failTransaction(Transaction txn, String reason) {
        stateMachine.transition(txn.getCurrentState(), TransactionStatus.FAILED);
        txn.setCurrentState(TransactionStatus.FAILED);
        txn.setFailureReason(reason);
        txn.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(txn);
        appendEvent(txn, txn.getCurrentState(), TransactionStatus.FAILED, reason);
        eventProducer.publishPaymentFailed(txn);
    }



}
