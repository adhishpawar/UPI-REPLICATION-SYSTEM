package com.upi.payment.domain.dto;


import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────────────────────
// TransactionStatusResponse
// ROLE: Lightweight status-only response for GET /payments/{id}/status
//       The mobile app polls this endpoint every 2 seconds after initiating
//       a payment. Returning only state + isTerminal avoids loading the full
//       audit trail on every poll.
//
// isTerminal: true means the payment is done (success or failure).
//             Mobile app stops polling when isTerminal = true.
// ─────────────────────────────────────────────────────────────────────────────
@Getter
@Builder
public class TransactionStatusResponse {
    private UUID   transactionId;
    private String currentState;    // e.g. "DEBIT_REQUESTED", "COMPLETED"
    private LocalDateTime lastUpdated;
    private boolean isTerminal;     // true = stop polling. false = keep polling.
}