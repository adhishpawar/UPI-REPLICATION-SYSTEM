package com.upi.bankservice.posting;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PostingRequest {

    /** The orchestrator's transaction id. Half of the idempotency key. */
    @NotBlank
    private String txId;

    /** DEBIT | CREDIT | REVERSAL. The other half of the idempotency key. */
    private String leg;

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal amount;

    private String rrn;

    /** Demo-only. See {@link FailureSimulator}. */
    private String simulate;
}
