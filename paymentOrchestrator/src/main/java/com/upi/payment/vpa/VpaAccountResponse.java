package com.upi.payment.vpa;

import lombok.*;

/**
 * VPA resolution including bank account details, from {@code vpa-service}'s
 * internal endpoint.
 *
 * <p>A payment cannot be executed against a display name, so the orchestrator
 * needs this. It is a separate response type from
 * {@link VpaResolutionResponse} because the two have different audiences: a
 * payer's app sees only a name, and only services that actually move money see
 * an account number.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VpaAccountResponse {
    private String  vpaAddress;
    /** Owner of this VPA. Used to verify the payer is spending their own money. */
    private java.util.UUID userId;
    private String  accountHolderName;
    private String  accountNumber;
    private String  ifscCode;
    private String  pspHandle;
    private Boolean isActive;
}
