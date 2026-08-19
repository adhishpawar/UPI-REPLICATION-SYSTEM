package com.upi.payment.domain.enums;

/**
 * The canonical payment lifecycle.
 *
 * <p>The original twelve states could express only two things about a funds
 * movement: it succeeded, or it failed. Real payment systems need a third,
 * and it is the one that matters most:
 *
 * <pre>
 *   UNCERTAIN  -- we asked the money-holder to move money, and we do not know
 *                 whether it did.
 * </pre>
 *
 * <p>Why that state has to exist. Suppose a credit request times out. There
 * are three tempting reactions and all three are wrong:
 *
 * <ul>
 *   <li>record CREDIT_FAILED and reverse the debit &rarr; if the credit had in
 *       fact succeeded, the payee keeps the money <em>and</em> the payer is
 *       refunded. <b>Money is created.</b></li>
 *   <li>record CREDITED &rarr; if the credit had in fact failed, the payer is
 *       out of pocket and nobody was paid. <b>Money disappears.</b></li>
 *   <li>retry the credit &rarr; if the first one landed, <b>the payee is paid
 *       twice.</b></li>
 * </ul>
 *
 * <p>The only correct reaction is to ask the money-holder what it actually
 * recorded, and decide afterwards. {@code UNCERTAIN} is the state that means
 * "do not act yet", and {@code RECONCILING} is the state that means "finding
 * out". Together they are the reason the self-healing subsystem exists: it is
 * not a bolt-on, it completes this state machine.
 *
 * <p>{@code MANUAL_REVIEW} is a legitimate outcome, not a defect. A system
 * that always resolves automatically is a system that guesses when it does
 * not know, and guessing about money is how money is created.
 */
public enum TransactionStatus {

    // ── Pre-money states. Nothing has moved; failure here is always safe. ──
    INITIATED,
    PAYEE_VALIDATED,
    FAILED,                 // terminal, no money moved

    // ── Debit leg ─────────────────────────────────────────────────────────
    DEBIT_REQUESTED,
    DEBIT_FAILED,           // terminal, no money moved
    DEBITED,                // money has LEFT the payer -- everything after
                            // this point must be compensated, not abandoned

    // ── Credit leg ────────────────────────────────────────────────────────
    CREDIT_REQUESTED,
    CREDITED,
    CREDIT_FAILED,          // known failure -> compensate
    COMPLETED,              // terminal, success

    // ── Compensation ──────────────────────────────────────────────────────
    REVERSAL_INITIATED,
    REVERSED,               // terminal, money returned to payer
    REVERSAL_FAILED,        // compensation itself failed -> a human must look

    // ── Uncertainty and self-healing ──────────────────────────────────────
    UNCERTAIN,              // outcome unknown. DO NOT retry. DO NOT reverse.
    RECONCILING,            // asking the money-holder what actually happened
    MANUAL_REVIEW;          // terminal, unresolvable automatically

    /**
     * True once money has provably left the payer. Past this line a payment
     * can never simply be abandoned: it must end in COMPLETED, REVERSED, or
     * MANUAL_REVIEW.
     */
    public boolean isAfterMoneyMoved() {
        return switch (this) {
            case DEBITED, CREDIT_REQUESTED, CREDITED, CREDIT_FAILED,
                 COMPLETED, REVERSAL_INITIATED, REVERSED, REVERSAL_FAILED,
                 UNCERTAIN, RECONCILING, MANUAL_REVIEW -> true;
            default -> false;
        };
    }

    /** True when the outcome of a funds movement is genuinely unknown. */
    public boolean isUncertain() {
        return this == UNCERTAIN || this == RECONCILING;
    }
}
