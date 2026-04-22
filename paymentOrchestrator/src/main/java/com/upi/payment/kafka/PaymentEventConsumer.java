package com.upi.payment.kafka;

import com.upi.payment.domain.event.*;
import com.upi.payment.saga.SagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final SagaOrchestrator sagaOrchestrator;

    /**
     * Idempotent consumer pattern:
     * 1. Process the event (SagaOrchestrator updates DB state)
     * 2. Commit the Kafka offset ONLY after DB is successfully updated
     * If service crashes between step 1 and 2, Kafka redelivers the message.
     * SagaOrchestrator.handle* methods use @Transactional — if DB save fails,
     * we do NOT acknowledge (ack), Kafka redelivers, we retry.
     */

    @KafkaListener(topics = "payment.debit.success", groupId = "payment-orchestrator-group")
    public void handleDebitSuccess(ConsumerRecord<String, DebitSuccessEvent> record,
                                   Acknowledgment ack)
    {
        log.info("Debit success received: txnId={}", record.value().getTransactionId());

        try
        {
            sagaOrchestrator.handleDebitSuccess(record.value());
            ack.acknowledge(); //Commit offset AFTER successful DB update
        }catch(Exception ex)
        {
            log.error("Error handling debit success: {}", ex.getMessage());
            // Do NOT acknowledge — Kafka will redeliver after retry timeout
            // After max retries, message goes to DLT (Dead Letter Topic

        }
    }

    @KafkaListener(topics = "payment.debit.failed", groupId = "payment-orchestrator-group")
    public void handleDebitFailed(ConsumerRecord<String, DebitFailedEvent> record,
                                  Acknowledgment ack)
    {
        log.warn("Debit failed received: txnId={} reason={}",
                record.value().getTransactionId(), record.value().getFailureReason());
        try {
            sagaOrchestrator.handleDebitFailure(record.value());
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Error handling debit failure: {}", ex.getMessage());
        }
    }

    @KafkaListener(topics = "payment.credit.success",
            groupId = "payment-orchestrator-group")
    public void handleCreditSuccess(ConsumerRecord<String, CreditSuccessEvent> record,
                                    Acknowledgment ack) {
        try {
            sagaOrchestrator.handleCreditSuccess(record.value());
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Error handling credit success: {}", ex.getMessage());
        }
    }

    @KafkaListener(topics = "payment.credit.failed",
            groupId = "payment-orchestrator-group")
    public void handleCreditFailed(ConsumerRecord<String, CreditFailedEvent> record,
                                   Acknowledgment ack) {
        try {
            sagaOrchestrator.handleCreditFailure(record.value());
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Error handling credit failure: {}", ex.getMessage());
        }
    }

    @KafkaListener(topics = "payment.reversal.success",
            groupId = "payment-orchestrator-group")
    public void handleReversalSuccess(ConsumerRecord<String, ReversalSuccessEvent> record,
                                      Acknowledgment ack) {
        try {
            sagaOrchestrator.handleReversalSuccess(record.value());
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Error handling reversal success: {}", ex.getMessage());
        }
    }

    // Dead Letter Topic consumer — handles messages that failed all retries
    @KafkaListener(topics = "payment.debit.requested.DLT",
            groupId = "payment-orchestrator-dlt-group")
    public void handleDebitDlt(ConsumerRecord<String, DebitRequestEvent> record) {
        log.error("DLT: debit event failed all retries. txnId={}",
                record.value().getTransactionId());
        // Alert: mark transaction as FAILED, notify operations team
        // Future: integrate with PagerDuty / alerting system
    }
}
