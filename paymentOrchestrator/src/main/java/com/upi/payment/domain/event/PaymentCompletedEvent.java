package com.upi.payment.domain.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;


@AllArgsConstructor
@Setter
@Getter
public class PaymentCompletedEvent {

    private UUID        transactionId;
    private String      rrn;
    private String      payerVpa;
    private String      payeeVpa;
    private String      payeeAccountHolderName;
    private BigDecimal  amount;
    private String      currency;
    private LocalDateTime completedAt;
    private String      correlationId;

    public PaymentCompletedEvent(UUID transactionId, String rrn, String payerVpa, String payeeVpa, BigDecimal amount, LocalDateTime completedAt) {
    }
}
