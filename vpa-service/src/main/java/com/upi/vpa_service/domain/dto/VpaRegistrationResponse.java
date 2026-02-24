package com.upi.vpa_service.domain.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Builder
public class VpaRegistrationResponse {
    private UUID vpaId;
    private String     vpaAddress;
    private String     accountHolderName;
    private String     pspHandle;
    private Boolean    isActive;
    private LocalDateTime createdAt;

    // accountNumber and ifscCode are NOT in the response (security)
}

