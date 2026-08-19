package com.upi.payment.mapper;

/*
 * ROLE: Converts between JPA Entities and DTOs (Data Transfer Objects).
 *       Sits inside the Service layer — called by PaymentServiceImpl.
 *
 * RULE: Entities never leave the service layer. DTOs never enter the DB layer.
 *       The Mapper is the wall between these two worlds.
 *
 * METHODS:
 *   toInitiationResponse  — Transaction → PaymentInitiationResponse (POST response)
 *   toDetailResponse      — Transaction → TransactionDetailResponse (GET full detail)
 *   toSummaryResponse     — Transaction → PaymentSummaryResponse (history list item)
 *   toEventDto            — TransactionEvent → TransactionEventDto (audit trail item)
 *
 * JAVA CONCEPT: @Component makes this a singleton Spring bean.
 *   @RequiredArgsConstructor injects TransactionEventRepository for loading
 *   events when building the detail response.
 *
 * WHY NOT MAPSTRUCT: Manual mapping is more explicit for learning.
 *   In production, MapStruct generates compile-time mappers that are faster
 *   and have zero runtime overhead. Replace these methods with @Mapper interface
 *   when you're ready to optimise.
 */

import com.upi.payment.domain.dto.*;
import com.upi.payment.domain.entity.Transaction;
import com.upi.payment.domain.entity.TransactionEvent;
import com.upi.payment.repository.TransactionEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PaymentMapper {

    // Injected to load event audit trail for detail response
    private final TransactionEventRepository eventRepository;

    /**
     * Maps Transaction → PaymentInitiationResponse.
     * Used immediately after saving the new transaction.
     * Events are NOT included here — only the basic initiation data.
     */
    public PaymentInitiationResponse toInitiationResponse(Transaction txn) {
        return PaymentInitiationResponse.builder()
                .transactionId(txn.getTransactionId())
                .rrn(txn.getRrn())
                .currentState(txn.getCurrentState().name())
                .payerVpa(txn.getPayerVpa())
                .payeeVpa(txn.getPayeeVpa())
                .payeeAccountHolderName(txn.getPayeeAccountHolderName())
                .amount(txn.getAmount())
                .currency(txn.getCurrency())
                .initiatedAt(txn.getInitiatedAt())
                .message("Payment initiated. Track status via GET /api/v1/payments/"
                        + txn.getTransactionId() + "/status")
                .build();
    }

    /**
     * Maps Transaction → TransactionDetailResponse.
     * Includes the full event audit trail — every state transition ever recorded.
     * This triggers a second DB query to load the events.

     * Java Streams used: convert List<TransactionEvent> → List<TransactionEventDto>
     * using .stream().map(this::toEventDto).collect(Collectors.toList())
     */
    public TransactionDetailResponse toDetailResponse(Transaction txn) {
        // Load the full audit trail for this transaction
        List<TransactionEvent> events = eventRepository
                .findByTransactionIdOrderByOccurredAtAsc(txn.getTransactionId());

        return TransactionDetailResponse.builder()
                .transactionId(txn.getTransactionId())
                .rrn(txn.getRrn())
                .currentState(txn.getCurrentState().name())
                .payerVpa(txn.getPayerVpa())
                .payeeVpa(txn.getPayeeVpa())
                .payeeAccountHolderName(txn.getPayeeAccountHolderName())
                .amount(txn.getAmount())
                .currency(txn.getCurrency())
                .remarks(txn.getRemarks())
                .failureReason(txn.getFailureReason())
                .initiatedAt(txn.getInitiatedAt())
                .completedAt(txn.getCompletedAt())
                .events(events.stream()
                        .map(this::toEventDto)
                        .collect(Collectors.toList()))
                .build();
    }

    /**
     * Maps Transaction → PaymentSummaryResponse.
     * Lightweight — used for the paginated history list.
     * Does NOT load events (would cause N+1 query problem on large history pages).
     */
    public PaymentSummaryResponse toSummaryResponse(Transaction txn) {
        return PaymentSummaryResponse.builder()
                .transactionId(txn.getTransactionId())
                .rrn(txn.getRrn())
                .payeeVpa(txn.getPayeeVpa())
                .payeeAccountHolderName(txn.getPayeeAccountHolderName())
                .amount(txn.getAmount())
                .currency(txn.getCurrency())
                .currentState(txn.getCurrentState().name())
                .initiatedAt(txn.getInitiatedAt())
                .completedAt(txn.getCompletedAt())
                .build();
    }

    /**
     * Maps TransactionEvent entity → TransactionEventDto.
     * Used inside toDetailResponse() via stream().map()
     */
    public TransactionEventDto toEventDto(TransactionEvent event) {
        return TransactionEventDto.builder()
                .fromState(event.getFromState().name())
                .toState(event.getToState().name())
                .description(event.getDescription())
                .triggeredBy(event.getTriggeredBy())
                .occurredAt(event.getOccurredAt())
                .build();
    }
}
