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
public class CreditRequestEvent {
    private UUID transactionId;
    private String rrn;
    private String payeeVpa;
    private String payeeAccountNumber;
    private String payeeIfscCode;
    private BigDecimal amount;
    private String correlationId;
}

