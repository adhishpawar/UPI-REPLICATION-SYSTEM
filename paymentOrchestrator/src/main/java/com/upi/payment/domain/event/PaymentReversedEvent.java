package com.upi.payment.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────────────────────
// PaymentReversedEvent
// PUBLISHED BY: Payment Orchestrator (topic: payment.reversed)
// CONSUMED BY:  Notification Service — sends "payment failed, money refunded" push
// ─────────────────────────────────────────────────────────────────────────────
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
class PaymentReversedEvent {
    private UUID transactionId;
    private String      rrn;
    private String      payerVpa;
    private BigDecimal amount;
    private String      failureReason;  // Why it was reversed
    private LocalDateTime reversedAt;
    private String      correlationId;
}