package com.upi.psp.domain.dto;

import lombok.*;
import org.hibernate.validator.constraints.UUID;


@Getter
@Builder
public class LoginResponse {

    private String accessToken;  // The JWT
    private UUID userId;
    private String tokenType;    // Always 'Bearer'
    private Long   expiresIn;    // Seconds until expiry
    // NO mpin, NO mpinHash, NO device fingerprint in response

}

