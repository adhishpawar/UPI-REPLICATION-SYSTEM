package com.upi.vpa_service.util;

import com.upi.vpa_service.exception.InvalidVpaFormatException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class VpaValidator {

    // This is the Flyweight pattern: share expensive-to-create objects
    private static final Pattern VPA_PATTERN =
            Pattern.compile("^[a-z0-9._]{3,50}@[a-z]{3,20}$");

    public void validateFormat(String vpaAddress) {
        if (vpaAddress == null || vpaAddress.isBlank()) {
            throw new InvalidVpaFormatException("VPA address cannot be blank");
        }

        // Normalize: always lowercase before validation
        String normalized = vpaAddress.toLowerCase().trim();

        // Pattern.matcher() creates a Matcher and runs the NFA
        if (!VPA_PATTERN.matcher(normalized).matches()) {
            throw new InvalidVpaFormatException(
                    "Invalid VPA format: '" + vpaAddress + "'. Must match: localpart@handle");
        }

        // Additional check: must contain exactly ONE '@'
        long atCount = normalized.chars().filter(c -> c == '@').count();
        if (atCount != 1) {
            throw new InvalidVpaFormatException("VPA must contain exactly one '@' character");
        }
    }

}
