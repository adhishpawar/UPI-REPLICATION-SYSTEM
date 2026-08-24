package com.upi.payment.domain.event;

import com.upi.payment.ports.FundsMovement;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * "Move this money." Payload of {@code DebitRequested},
 * {@code CreditRequested} and {@code ReversalRequested}.
 *
 * <p>A record, so it is immutable. Events describe things that have been
 * decided; a mutable event is a thing that can be quietly altered between
 * being produced and being consumed, which makes an audit trail worthless.
 *
 * @param simulate demo-only failure injection, carried through so a scenario
 *                 named on the original request still applies when the command
 *                 is eventually consumed. Null in normal operation.
 */
public record FundsCommand(UUID transactionId,
                           String rrn,
                           FundsMovement.Leg leg,
                           String accountNumber,
                           BigDecimal amount,
                           String currency,
                           String traceId,
                           String simulate) { }
