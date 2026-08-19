package com.upi.payment.domain.event;

import com.upi.payment.ports.FundsMovement;

import java.util.UUID;

/**
 * "Here is what happened to that money." Payload of every {@code *Succeeded},
 * {@code *Failed} and {@code FundsOutcomeUnknown} event.
 *
 * <p>{@code outcome} carries three values, not two. Consumers must handle
 * {@link FundsMovement.Outcome#UNKNOWN} explicitly rather than folding it into
 * failure -- see {@link com.upi.payment.domain.enums.TransactionStatus} for
 * what folding it costs.
 */
public record FundsOutcome(UUID transactionId,
                           String rrn,
                           FundsMovement.Leg leg,
                           FundsMovement.Outcome outcome,
                           String reference,
                           String failureReason,
                           String traceId) { }
