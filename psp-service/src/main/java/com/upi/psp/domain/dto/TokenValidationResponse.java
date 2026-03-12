package com.upi.psp.domain.dto;


import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class TokenValidationResponse {

    // true if JWT is valid, not expired, not revoked.
    private boolean valid;

    private UUID userId;

    // Extracted from JWT 'sub' claim. Null if valid=false.
    private String deviceId;

    // Extracted from JWT 'roles' custom claim. E.g. ["ROLE_USER"].
    private List<String> roles;

    // JWT expiry time. Client can use this to decide when to refresh.
    private LocalDateTime expiresAt;

    // Reason for invalidity. Null if valid=true.
    private String invalidReason;

}
