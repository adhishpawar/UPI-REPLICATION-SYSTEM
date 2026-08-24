package com.upi.payment.domain.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentInitiatedEvent {
    private UUID transactionId;
    private String rrn;
    private String payerVpa;
    private String payeeVpa;
    private BigDecimal amount;
    private String currency;
    private LocalDateTime initiatedAt;
    private String correlationId;  // For distributed tracing

}

