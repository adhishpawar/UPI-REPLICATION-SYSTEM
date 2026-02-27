package com.upi.psp.mapper;

import com.upi.psp.domain.dto.UserRegistrationResponse;
import com.upi.psp.domain.entity.User;
import org.springframework.stereotype.Component;

@Component
public class AuthMapper {

    /**
     * Converts a saved User entity → UserRegistrationResponse DTO.
     *
     * SECURITY: mpinHash and deviceFingerprint are NOT mapped.
     *           The mobile number is MASKED before inclusion.
     *           Only safe, non-sensitive fields go into the response.
     *
     * @param user the saved User entity (after DB insert)
     * @return DTO safe to serialize into the HTTP response body
     */
    public UserRegistrationResponse toRegistrationResponse(User user) {
        return UserRegistrationResponse.builder()
                .userId(user.getUserId())
                .maskedMobile(maskMobile(user.getMobileNumber()))
                .deviceId(user.getDeviceId())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .message("Registration successful. Please set your MPIN to activate your account.")
                .build();
    }

    /**
     * Mask mobile number for safe display.
     * Input:  "+911234567890"
     * Output: "+91****7890"
     *
     * Algorithm:
     *   - Keep country code prefix (first 3 chars for +91)
     *   - Replace middle chars with ****
     *   - Keep last 4 digits for identification
     *
     * Why mask? Mobile number is PII (Personally Identifiable Information).
     * Even in the registration response, we shouldn't echo back the full number.
     * The user already knows their own number — partial display is enough
     * for confirmation without unnecessarily exposing the full number in logs
     * or network captures.
     */
    private String maskMobile(String normalizedMobile) {
        if (normalizedMobile == null || normalizedMobile.length() < 7) {
            return "****";
        }
        // E.164 format: +91XXXXXXXXXX (13 chars for India)
        // Keep: "+91" (3 chars) + "****" + last 4 digits
        int totalLength = normalizedMobile.length();
        String prefix   = normalizedMobile.substring(0, 3);         // "+91"
        String lastFour = normalizedMobile.substring(totalLength - 4); // "7890"
        return prefix + "****" + lastFour;
    }
}
