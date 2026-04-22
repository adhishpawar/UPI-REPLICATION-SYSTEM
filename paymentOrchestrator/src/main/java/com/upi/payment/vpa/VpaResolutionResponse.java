package com.upi.payment.vpa;

// ─────────────────────────────────────────────────────────────────────────────
// VpaResolutionResponse.java
// ROLE: DTO received from VPA Service when resolving a payee VPA.
//       VpaServiceClient.resolveVpa() deserializes the HTTP response into this.
//       SagaOrchestrator uses accountHolderName to update the Transaction entity
//       so it can be shown in payment history ("paid to Priya Sharma").
// ─────────────────────────────────────────────────────────────────────────────

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