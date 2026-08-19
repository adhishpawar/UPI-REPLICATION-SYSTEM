package com.upi.payment.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;


//Bank Debit Adapter publishes this on success
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditSuccessEvent {
    private UUID transactionId;
    private String bankReferenceNumber;  // Bank's own reference
    private String correlationId;
}

