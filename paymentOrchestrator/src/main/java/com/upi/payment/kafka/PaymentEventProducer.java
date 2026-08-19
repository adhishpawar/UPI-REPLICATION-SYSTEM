package com.upi.payment.kafka;

import com.upi.payment.domain.entity.Transaction;
import com.upi.payment.domain.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    //Topic name constants - single source of truth
    public static final String TOPIC_DEBIT_REQUEST    = "payment.debit.requested";
    public static final String TOPIC_CREDIT_REQUEST   = "payment.credit.requested";
    public static final String TOPIC_REVERSAL_REQUEST = "payment.reversal.requested";
    public static final String TOPIC_COMPLETED        = "payment.completed";
    public static final String TOPIC_REVERSED         = "payment.reversed";
    public static final String TOPIC_FAILED           = "payment.failed";

    /*
    publish debit request to Bank Debit Adapter
    key = trxId - kafka uses this for partition routing
    same transactionId always goes to same partition -> ordered processing
    */
    public void publishDebitRequest(Transaction txn){
        DebitRequestEvent event = DebitRequestEvent.builder()
                .transactionId(txn.getTransactionId())
                .rrn(txn.getRrn())
                .payerVpa(txn.getPayerVpa())
                .amount(txn.getAmount())
                .correlationId(txn.getTransactionId().toString())
                .build();
        send(TOPIC_DEBIT_REQUEST, txn.getTransactionId().toString(), event);
    }

    public void publishCreditRequest(Transaction txn){
        CreditRequestEvent event = CreditRequestEvent.builder()
                .transactionId(txn.getTransactionId())
                .rrn(txn.getRrn())
                .payeeVpa(txn.getPayeeVpa())
                .amount(txn.getAmount())
                .correlationId(txn.getTransactionId().toString())
                .build();
        send(TOPIC_CREDIT_REQUEST, txn.getTransactionId().toString(), event);
    }

    public void publishReversalRequest(Transaction txn) {
        ReversalRequestEvent event = ReversalRequestEvent.builder()
                .transactionId(txn.getTransactionId())
                .rrn(txn.getRrn())
                .payerVpa(txn.getPayerVpa())
                .amount(txn.getAmount())
                .originalDebitReference(txn.getBankDebitReferenceNumber())
                .build();
        send(TOPIC_REVERSAL_REQUEST, txn.getTransactionId().toString(), event);
    }

    public void publishPaymentCompleted(Transaction txn) {
        send(TOPIC_COMPLETED, txn.getTransactionId().toString(),
                buildCompletedEvent(txn));
    }

    //Added by my own
    public void publishPaymentReversed(Transaction txn) {
        send(TOPIC_REVERSED, txn.getTransactionId().toString(),
                buildCompletedEvent(txn));
    }

    public void publishPaymentFailed(Transaction txn) {
        send(TOPIC_FAILED, txn.getTransactionId().toString(),
                buildFailedEvent(txn));
    }

    private void send(String topic, String key, Object event) {
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka send FAILED topic={} key={}: {}",
                                topic, key, ex.getMessage());
                        // In production: use Outbox table for guaranteed delivery
                    } else {
                        log.debug("Kafka sent topic={} partition={} offset={}",
                                topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    private Object buildCompletedEvent(Transaction txn) {
        return new PaymentCompletedEvent(txn.getTransactionId(),
                txn.getRrn(), txn.getPayerVpa(), txn.getPayeeVpa(),
                txn.getAmount(), txn.getCompletedAt());
    }

    private Object buildFailedEvent(Transaction txn) {
        return new PaymentFailedEvent(txn.getTransactionId(),
                txn.getRrn(), txn.getFailureReason());
    }



}
