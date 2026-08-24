package com.upi.payment.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class PaymentInitiationResponse {
    private UUID transactionId;
    private String rrn;
    private String currentState;  // "INITIATED"
    private String payerVpa;
    private String payeeVpa;
    private String payeeAccountHolderName;
    private BigDecimal amount;
    private String currency;
    private LocalDateTime initiatedAt;
    private String message;  // "Payment initiated. Track via transactionId."
}

