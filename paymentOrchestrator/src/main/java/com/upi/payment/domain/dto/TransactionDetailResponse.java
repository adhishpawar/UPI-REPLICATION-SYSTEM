package com.upi.payment.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// returned by GET /payments/{id}
@Getter
@Builder
public class TransactionDetailResponse {
    private UUID transactionId;
    private String rrn;
    private String currentState;
    private String payerVpa;
    private String payeeVpa;
    private String payeeAccountHolderName;
    private BigDecimal amount;
    private String currency;
    private String failureReason;
    private LocalDateTime initiatedAt;
    private LocalDateTime completedAt;
    private List<TransactionEventDto> events;  // Full audit trail

}
