package com.upi.payment.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

//sent to Bank Credit Adapter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversalRequestEvent {
    private UUID        transactionId;
    private String      rrn;
    private String      payerVpa;
    private BigDecimal  amount;
    private String      originalDebitReference;  // From Transaction.bankDebitReferenceNumber
    private String      correlationId;
    private LocalDateTime requestedAt;
}

