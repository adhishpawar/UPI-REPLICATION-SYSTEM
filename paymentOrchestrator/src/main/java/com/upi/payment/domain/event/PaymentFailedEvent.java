package com.upi.payment.domain.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;


@AllArgsConstructor
@Setter
@Getter
public class PaymentFailedEvent {

    private UUID transactionId;
    private String rrn;
    private String failureReason;
    private String correlationId;

    public PaymentFailedEvent(UUID transactionId, String rrn, String failureReason) {
    }
}
