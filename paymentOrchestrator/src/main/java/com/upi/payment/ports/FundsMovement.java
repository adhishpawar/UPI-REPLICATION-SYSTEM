package com.upi.payment.ports;

import com.upi.payment.domain.enums.FundingSource;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Value types for moving money, shared by every {@link FundsMover}.
 *
 * <p>The design decision that matters here is {@link Outcome}. Most APIs model
 * a write as succeeding or failing. A funds movement has a third possibility
 * that is not a variety of failure and must never be collapsed into one.
 */
public final class FundsMovement {

    private FundsMovement() { }

    /** Which side of a payment a posting belongs to. */
    public enum Leg { DEBIT, CREDIT, REVERSAL }

    /**
     * What we know about a funds movement.
     *
     * <pre>
     *   SUCCEEDED  the money moved, and we have the reference to prove it
     *   FAILED     the money did NOT move, and the money-holder told us so
     *   UNKNOWN    we asked; we did not get an answer we can trust
     * </pre>
     *
     * <p>{@code UNKNOWN} is the whole reason the self-healing subsystem
     * exists. A timeout produces {@code UNKNOWN}, never {@code FAILED} -- the
     * request may well have been processed and the response lost on the way
     * back. Treating that as failure and reversing the debit refunds a payer
     * who was also paid, which creates money out of nothing.
     *
     * <p>The only correct response to {@code UNKNOWN} is to ask the
     * money-holder what it actually recorded, and decide afterwards.
     */
    public enum Outcome { SUCCEEDED, FAILED, UNKNOWN }

    /**
     * A request to move money.
     *
     * @param transactionId the payment this belongs to. Also the idempotency
     *                      key at the money-holder: combined with {@code leg}
     *                      it uniquely identifies one posting, so a repeat
     *                      posts nothing and returns the original result.
     * @param simulate      demo-only failure injection. Null in normal
     *                      operation. See docs/testing/failure-scenarios.md.
     */
    public record Command(UUID transactionId,
                          String rrn,
                          Leg leg,
                          String accountNumber,
                          BigDecimal amount,
                          String currency,
                          String traceId,
                          String simulate) { }

    /**
     * The result of asking for a movement.
     *
     * @param balanceAfter present only when the money-holder chose to disclose
     *                     it; never relied upon for a decision.
     */
    public record Result(Outcome outcome,
                         String reference,
                         String failureReason,
                         BigDecimal balanceAfter) {

        public static Result succeeded(String reference, BigDecimal balanceAfter) {
            return new Result(Outcome.SUCCEEDED, reference, null, balanceAfter);
        }

        public static Result failed(String reason) {
            return new Result(Outcome.FAILED, null, reason, null);
        }

        /** Use for timeouts, connection failures, and unparseable responses. */
        public static Result unknown(String reason) {
            return new Result(Outcome.UNKNOWN, null, reason, null);
        }
    }

    /**
     * What the money-holder says it recorded for a transaction leg.
     *
     * <p>This is the answer to the reconciliation question, and it is why the
     * query exists separately from the command: asking "what did you record?"
     * is a read, and a read is always safe to repeat. Re-issuing the command
     * is not.
     */
    public record LedgerRecord(boolean found,
                               Leg leg,
                               String status,
                               String reference,
                               BigDecimal amount) {

        public static LedgerRecord notFound(Leg leg) {
            return new LedgerRecord(false, leg, "NOT_FOUND", null, null);
        }
    }

    /** Thrown when the money-holder cannot be reached at all. */
    public static class MoverUnavailableException extends RuntimeException {
        public MoverUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Marker so callers can tie a result back to a funding source. */
    public interface HasFundingSource {
        FundingSource fundingSource();
    }
}
