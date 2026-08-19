package com.upi.payment.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────────────────────
// PaymentSummaryResponse
// ROLE: One item in the paginated GET /payments/history list.
//       Deliberately lightweight — no events list (avoids N+1 DB queries).
//       Shows just enough for a payment history UI row.
// ─────────────────────────────────────────────────────────────────────────────
@Getter
@Builder
public class PaymentSummaryResponse {
    private UUID   transactionId;
    private String rrn;
    private String payeeVpa;
    private String payeeAccountHolderName;  // "Priya Sharma" — shown in history
    private java.math.BigDecimal amount;
    private String currency;
    private String currentState;
    private LocalDateTime initiatedAt;
    private LocalDateTime completedAt;      // null if still in progress
}
