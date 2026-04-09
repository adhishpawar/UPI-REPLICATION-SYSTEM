package com.upi.payment.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

//sent to Bank Credit Adapter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversalRequestEvent {
    private UUID transactionId;
    private String rrn;
    private String payerVpa;
    private String payerAccountNumber;
    private String payerIfscCode;
    private BigDecimal amount;
    private String correlationId;

    private String originalDebitReference;
}

