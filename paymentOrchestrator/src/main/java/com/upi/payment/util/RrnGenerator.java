package com.upi.payment.util;

/**
 * ROLE: Generates a unique RRN (Retrieval Reference Number) for each payment.
 *
 * WHAT IS RRN:
 *   RRN is a 12-digit alphanumeric code assigned to every UPI transaction.
 *   It is the primary reference number used between banks for reconciliation.
 *   When a customer calls their bank about a payment, they quote the RRN.
 *   Format used here: RRN + YYMMDDHHmmss + 6-digit random = 22 chars total.
 *
 * ALGORITHM: Timestamp prefix + SecureRandom suffix
 *   Why timestamp prefix?
 *   - Naturally sortable: newer RRNs are lexicographically greater
 *   - Partitioned by time: easier to shard/archive old records
 *   - Debuggable: you can read the approximate creation time from the RRN
 *
 *   Why SecureRandom not Random?
 *   - java.util.Random is predictable — seed is based on current time.
 *     An attacker could predict future RRNs if they know the current one.
 *   - java.security.SecureRandom uses OS entropy pool (/dev/urandom on Linux).
 *     Cryptographically unpredictable. Safe for financial identifiers.
 *
 * UNIQUENESS GUARANTEE:
 *   Collision probability with 6-digit suffix: 1 in 1,000,000 per second.
 *   For extra safety, TransactionRepository.existsByRrn() verifies uniqueness
 *   before accepting the generated value. Retry on collision (extremely rare).
 *
 * THREAD SAFETY:
 *   SecureRandom is thread-safe — safe to use as a singleton @Component.
 *   DateTimeFormatter is immutable — thread-safe by design.
 */

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@Slf4j
public class RrnGenerator {

    // SecureRandom: cryptographically strong random number generator
    // Singleton — one instance per application, thread-safe
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // DateTimeFormatter is immutable — safe for concurrent use
    // Pattern: YYMMDDHHmmss = 12 chars (year2, month, day, hour, min, sec)
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyMMddHHmmss");

    // Total RRN length: 3 (prefix) + 12 (timestamp) + 6 (random) = 21 chars
    private static final String RRN_PREFIX = "RRN";
    private static final int RANDOM_SUFFIX_LENGTH = 6;

    /**
     * Generate a unique RRN.
     * Format: RRN + 240327101530 + 847291
     * Example: RRN240327101530847291
     *
     * @return 21-character alphanumeric RRN
     */
    public String generate() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String randomSuffix = generateNumericSuffix(RANDOM_SUFFIX_LENGTH);
        String rrn = RRN_PREFIX + timestamp + randomSuffix;
        log.debug("Generated RRN: {}", rrn);
        return rrn;
    }

    /**
     * Generate a secure numeric string of given length.
     * Uses SecureRandom to produce each digit independently.
     *
     * Algorithm: for each position, compute (secureRandom.nextInt(10))
     * to get a digit 0-9. This gives uniform distribution across all digits.
     *
     * JAVA STREAM: IntStream.range generates indices 0..length-1,
     *   mapToObj converts each to a digit char,
     *   collect joins them into a String.
     *
     * Time complexity: O(n) where n = length
     */
    private String generateNumericSuffix(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));  // 0-9 inclusive
        }
        return sb.toString();
    }

    /**
     * Validate an RRN format — used in testing and inbound event validation.
     * Checks: starts with "RRN", total length 21, all alphanumeric.
     */
    public boolean isValidRrn(String rrn) {
        if (rrn == null) return false;
        return rrn.startsWith(RRN_PREFIX)
                && rrn.length() == 21
                && rrn.matches("^[A-Z0-9]+$");
    }
}
