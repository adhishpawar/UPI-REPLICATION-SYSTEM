package com.upi.payment.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// sent to Bank Debit Adapter
public class DebitRequestEvent {
    private UUID transactionId;
    private String rrn;
    private String payerVpa;
    private String payerAccountNumber;  // Resolved from VPA Service
    private String payerIfscCode;
    private BigDecimal amount;
    private String correlationId;
}
