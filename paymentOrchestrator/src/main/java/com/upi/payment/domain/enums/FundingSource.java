package com.upi.payment.domain.enums;

/**
 * Where the money for a payment comes from, and where it goes.
 *
 * <p>Decision D-003: the digital wallet is NOT a second payment system. It is
 * a second money-holder behind the same saga, the same state machine, the same
 * idempotency rules and the same self-healing. Modelling it as a parallel
 * platform would duplicate all of those, and therefore duplicate every
 * correctness bug in both.
 *
 * <p>The practical consequence: wallet-to-wallet transfer is not a new
 * feature. It is an ordinary payment where both legs happen to be WALLET.
 */
public enum FundingSource {
    BANK,
    WALLET
}
