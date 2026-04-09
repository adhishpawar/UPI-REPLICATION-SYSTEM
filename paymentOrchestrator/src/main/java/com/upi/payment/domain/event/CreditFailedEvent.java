package com.upi.payment.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

//Bank Debit Adapter publishes on failure
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditFailedEvent {
    private UUID transactionId;
    private String failureReason;  // INSUFFICIENT_FUNDS, ACCOUNT_BLOCKED, etc.
    private String correlationId;
}

