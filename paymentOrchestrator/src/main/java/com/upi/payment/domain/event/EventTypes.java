package com.upi.payment.domain.event;

/**
 * The platform's event vocabulary, in one place.
 *
 * <p>Two categories, and keeping them distinct is what stops an event system
 * decaying into an untraceable mesh:
 *
 * <ul>
 *   <li><b>Commands</b> ({@code *Requested}) are addressed to exactly one
 *       consumer and ask for something to happen. If two consumers handle a
 *       command, it happens twice.</li>
 *   <li><b>Facts</b> (past tense) describe something that already happened and
 *       may have any number of consumers, including none.</li>
 * </ul>
 *
 * <p>The practical test: a fact with no consumer is fine -- nobody currently
 * cares that a payment completed. A <em>command</em> with no consumer is a
 * broken system, and that was precisely the platform's condition before this
 * work: the saga published {@code DebitRequested} and nothing, anywhere,
 * consumed it.
 */
public final class EventTypes {

    private EventTypes() { }

    // ── Commands: exactly one consumer each ───────────────────────────────
    public static final String DEBIT_REQUESTED    = "DebitRequested";
    public static final String CREDIT_REQUESTED   = "CreditRequested";
    public static final String REVERSAL_REQUESTED = "ReversalRequested";

    // ── Facts: any number of consumers ────────────────────────────────────
    public static final String PAYMENT_INITIATED  = "PaymentInitiated";
    public static final String PAYEE_VALIDATED    = "PayeeValidated";
    public static final String DEBIT_SUCCEEDED    = "DebitSucceeded";
    public static final String DEBIT_FAILED       = "DebitFailed";
    public static final String CREDIT_SUCCEEDED   = "CreditSucceeded";
    public static final String CREDIT_FAILED      = "CreditFailed";
    public static final String REVERSAL_SUCCEEDED = "ReversalSucceeded";
    public static final String REVERSAL_FAILED    = "ReversalFailed";
    public static final String PAYMENT_COMPLETED  = "PaymentCompleted";
    public static final String PAYMENT_FAILED     = "PaymentFailed";
    public static final String PAYMENT_REVERSED   = "PaymentReversed";

    /**
     * The outcome of a funds movement is unknown.
     *
     * <p>The event that makes self-healing possible. It is deliberately not a
     * variety of {@code *Failed}: a consumer that treats it as failure will
     * compensate a debit that may have succeeded.
     */
    public static final String FUNDS_OUTCOME_UNKNOWN = "FundsOutcomeUnknown";

    // ── Recovery ──────────────────────────────────────────────────────────
    public static final String RECOVERY_STARTED   = "RecoveryStarted";
    public static final String RECOVERY_DECIDED   = "RecoveryDecided";
    public static final String RECONCILIATION_MISMATCH = "ReconciliationMismatch";

    public static final String AGGREGATE_TRANSACTION = "Transaction";
}
