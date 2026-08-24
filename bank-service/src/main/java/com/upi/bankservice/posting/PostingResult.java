package com.upi.bankservice.posting;

import com.upi.bankservice.models.Ledger;

import java.math.BigDecimal;

/**
 * The outcome of a posting request.
 *
 * <p>{@code replayed} distinguishes "we just moved money" from "we moved it
 * earlier and are telling you again". The caller does not need to behave
 * differently -- that is the point of idempotency -- but the distinction is
 * worth surfacing, because a replay rate that suddenly climbs is a signal that
 * something upstream is retrying more than it should.
 */
public record PostingResult(String reference,
                            String status,
                            BigDecimal amount,
                            BigDecimal balanceAfter,
                            boolean replayed) {

    public static PostingResult posted(Ledger l) {
        return new PostingResult(l.getReference(), l.getStatus().name(),
                l.getAmount(), l.getBalanceAfter(), false);
    }

    public static PostingResult replay(Ledger l) {
        return new PostingResult(l.getReference(), l.getStatus().name(),
                l.getAmount(), l.getBalanceAfter(), true);
    }
}
