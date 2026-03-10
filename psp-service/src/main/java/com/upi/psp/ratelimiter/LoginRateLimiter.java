package com.upi.psp.ratelimiter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    //Java Collections: ConcurrentHashMap for thread safe bucket Storage
    //ConcurrentHashMap --> uses lock Striping only lock a segment not the whole map
    //This allows high concurrency without blocking threads unnecessarily

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final int CAPACITY = 5;
    private static final int REFILL_MINUTES = 15;
    private static final int REFILL_TOKENS = 5;

    /**
     * Try to consume 1 token from the bucket for this mobile number.
     * Returns true if allowed, false if rate limited.
     * Thread-safe: ConcurrentHashMap.computeIfAbsent() is atomic.
     */

    public boolean tryConsume(String mobileNumber) {
        //ComputeIfAbsent --> if key not present --create new Bucket Atomically
        //This is thread - safe --> even under high concurrency
        Bucket bucket = buckets.computeIfAbsent(mobileNumber, this::createBucket);

        //tryConsume(1) : take 1 toke if avail
        //Returns true (token consumed) or false (empty, rate limited)
        return bucket.tryConsume(1);
    }

    /**
     * Reset the bucket on successful login.
     * Allows next 5 attempts to start fresh.
     */

    public void resetBucket(String mobileNumber) {
        buckets.remove(mobileNumber);
    }

    private Bucket createBucket(String mobileNumber) {
        //Bandwidth - Refill REFILL_TOKENS tokens every REFILL_MINUTES min
        Bandwidth limit = Bandwidth.classic(
                CAPACITY,
                Refill.intervally(REFILL_TOKENS, Duration.ofMinutes(REFILL_MINUTES))
        );
        return Bucket.builder().addLimit(limit).build();
    }
}
