package com.upi.vpa_service.domain.dto;

import lombok.*;

@Getter @Builder
public class VpaResolutionResponse {
    private String  vpaAddress;
    private String  accountHolderName;   // payer for confirmation
    private String  pspHandle;
    private Boolean isActive;

    // Sensitive bank details NOT included — they are fetched via secure channel
}

