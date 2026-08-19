package com.upi.vpa_service.domain.dto;

import lombok.*;

/**
 * VPA resolution <b>including</b> bank account details.
 *
 * <p>Deliberately separate from {@link VpaResolutionResponse}, which is what a
 * payer's app is shown and which discloses only the account holder's name.
 *
 * <p>The distinction is the point. A payer confirming "am I paying the right
 * person?" needs a name. A payment orchestrator preparing to move money needs
 * the account number. Returning both to everyone would mean any caller that can
 * guess a VPA can enumerate account numbers, so the two audiences get two
 * endpoints.
 *
 * <p><b>Current limitation, stated plainly:</b> the internal endpoint is not
 * authenticated, because no service-to-service auth exists yet in this
 * platform. In a real deployment it would sit behind mutual TLS or a
 * service token, and the account number would be encrypted at rest rather than
 * stored in the clear (the migration comment claims AES-256; the code does not
 * implement it). Tracked in known-gaps.
 */
@Getter
@Builder
public class VpaAccountResponse {
    private String  vpaAddress;
    private String  accountHolderName;
    private String  accountNumber;
    private String  ifscCode;
    private String  pspHandle;
    private Boolean isActive;
}
