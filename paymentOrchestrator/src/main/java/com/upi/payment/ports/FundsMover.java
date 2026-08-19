package com.upi.payment.ports;

import com.upi.payment.domain.enums.FundingSource;

import java.util.UUID;

/**
 * A holder of money that can be asked to move it.
 *
 * <p>This is the seam the whole platform is organised around. Decision D-003:
 * the digital wallet is not a second payment system, it is a second
 * implementation of this interface. Everything upstream -- the saga, the state
 * machine, idempotency, compensation, reconciliation, self-healing -- is
 * written once against this port and works for both.
 *
 * <p>The alternative, building the wallet as a parallel platform with its own
 * saga and its own transaction table, duplicates all of that logic and
 * therefore duplicates every correctness bug in it. Wallet-to-wallet transfer
 * also stops being a new feature: it is an ordinary payment where both legs
 * happen to be {@link FundingSource#WALLET}.
 *
 * <h3>Contract</h3>
 *
 * <ol>
 *   <li><b>Idempotent by (transactionId, leg).</b> Calling {@code debit} twice
 *       for the same transaction must move money once and return the same
 *       result both times.</li>
 *   <li><b>Never lie about uncertainty.</b> A timeout or unreachable
 *       money-holder must produce {@code UNKNOWN}, not {@code FAILED}.
 *       Reporting a timeout as failure is what allows a reversal to be issued
 *       against a debit that actually succeeded.</li>
 *   <li><b>{@link #query} is a read.</b> It must have no side effects and must
 *       be safe to call any number of times, because recovery calls it while
 *       deciding what is true.</li>
 * </ol>
 */
public interface FundsMover {

    /** Which funding source this implementation speaks for. */
    FundingSource fundingSource();

    /** Take money out of an account. Idempotent by (transactionId, DEBIT). */
    FundsMovement.Result debit(FundsMovement.Command command);

    /** Put money into an account. Idempotent by (transactionId, CREDIT). */
    FundsMovement.Result credit(FundsMovement.Command command);

    /**
     * Compensate a committed debit.
     *
     * <p>Not a rollback. The original debit is committed and visible and
     * cannot be undone; this posts a semantically inverse entry. Both remain
     * in the ledger, which is the point -- the audit trail shows what happened
     * rather than a tidied version of it.
     */
    FundsMovement.Result reverse(FundsMovement.Command command);

    /**
     * Ask what was actually recorded for one leg of a transaction.
     *
     * <p>The reconciliation primitive. Recovery calls this instead of retrying
     * a movement whose outcome is unknown, because a read cannot create money
     * and a repeated write can.
     */
    FundsMovement.LedgerRecord query(UUID transactionId, FundsMovement.Leg leg,
                                     String accountNumber);
}
