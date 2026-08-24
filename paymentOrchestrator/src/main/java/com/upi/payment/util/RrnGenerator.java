package com.upi.payment.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Generates the RRN (Retrieval Reference Number) for a payment.
 *
 * <p>The RRN is the reference a human quotes: the number on the receipt, the
 * number the customer reads out to their bank, the number two banks use to
 * talk about the same payment. Real NPCI RRNs are <b>12 digits</b>.
 *
 * <p><b>Format change from the previous version.</b> The generator produced a
 * 21-character value ({@code RRN} + 12-digit timestamp + 6 random digits)
 * while the design document specified {@code VARCHAR(12)}. Twelve digits is
 * both the realistic length and the documented one, so the format is now
 * {@code yyMMdd} + 6 random digits. The two good instincts in the original are
 * kept intact:
 *
 * <ul>
 *   <li><b>Time prefix.</b> RRNs sort naturally by age, which makes archiving
 *       and range queries cheap and makes the value readable during
 *       debugging.</li>
 *   <li><b>{@link SecureRandom}, not {@link java.util.Random}.</b>
 *       {@code Random} is seeded from the clock and is trivially predictable:
 *       knowing one value lets you guess the next. For an identifier that
 *       appears on customer receipts and in inter-bank messages, predictable
 *       is not acceptable. {@code SecureRandom} draws from the OS entropy
 *       pool.</li>
 * </ul>
 *
 * <p><b>Uniqueness.</b> A 6-digit suffix collides roughly once per million
 * payments <em>within the same day</em>, which is not rare enough to ignore.
 * Two independent defences handle it: the caller retries on a detected
 * collision, and {@code transactions.rrn} carries a UNIQUE constraint. The
 * constraint is the real guarantee; the retry just avoids surfacing an error
 * for something the system can resolve itself.
 *
 * <p>Thread-safe: {@code SecureRandom} and {@code DateTimeFormatter} are both
 * safe for concurrent use, so this can be a singleton bean.
 */
@Component
@Slf4j
public class RrnGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 6 chars: yyMMdd. Leaves 6 for randomness within the 12-digit budget. */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyMMdd");

    private static final int RANDOM_SUFFIX_LENGTH = 6;
    public static final int RRN_LENGTH = 12;

    /**
     * Generate a 12-digit RRN, e.g. {@code 260819847291}.
     * Not guaranteed unique on its own -- see the class comment.
     */
    public String generate() {
        String rrn = LocalDateTime.now().format(DATE_FORMAT)
                + generateNumericSuffix(RANDOM_SUFFIX_LENGTH);
        log.debug("Generated RRN: {}", rrn);
        return rrn;
    }

    private String generateNumericSuffix(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** Validate shape only. Used by tests and inbound event validation. */
    public boolean isValidRrn(String rrn) {
        return rrn != null
                && rrn.length() == RRN_LENGTH
                && rrn.matches("^[0-9]{12}$");
    }
}
