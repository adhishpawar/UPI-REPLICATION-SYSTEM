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

    private UUID transactionId;
    private String rrn;
    private String payer;
    private String payeeVpa;
    private BigDecimal amount;
    private LocalDateTime completedAt;

}
