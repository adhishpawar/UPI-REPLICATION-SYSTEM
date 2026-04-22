package com.upi.payment.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;


//Bank Debit Adapter publishes this on success
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversalSuccessEvent {
    private UUID   transactionId;
    private String reversalBankReference;  // Bank's reference for the reversal transaction
    private String correlationId;
    private LocalDateTime reversedAt;
}

