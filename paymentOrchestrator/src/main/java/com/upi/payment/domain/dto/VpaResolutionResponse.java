package com.upi.payment.domain.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VpaResolutionResponse {
    private String  vpaAddress;
    private String  accountHolderName;  // Display name: "Priya Sharma"
    private String  pspHandle;          // e.g. "ybl"
    private Boolean isActive;           // Must be true to proceed
}
