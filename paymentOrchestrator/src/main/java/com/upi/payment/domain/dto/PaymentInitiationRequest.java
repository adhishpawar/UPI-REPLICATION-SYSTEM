package com.upi.payment.domain.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
public class PaymentInitiationRequest {


    //Note --> IdempotencyKey comes from the Idempotency Key HTTP header
    //Not the request Body -> it is set by the IdempotencyFilter

    @NotBlank(message = "Payer VPA required")
    @Pattern(regexp = "^[a-z0-9._]{3,50}@[a-z]{3,20}$")
    private String payerVpa;

    @NotBlank(message = "Payee VPA required")
    @Pattern(regexp = "^[a-z0-9._]{3,50}@[a-z]{3,20}$")
    private String payeeVpa;

    @NotNull(message = "Amount required")
    @DecimalMin(value = "0.01", message = "Amount must be > 0")
    @DecimalMax(value = "200000.00", message = "Amount exceeds NPCI limit of ₹2 Lakh")
    @Digits(integer = 13, fraction = 2, message = "Invalid amount format")
    private BigDecimal amount;

    @NotBlank @Size(max = 3)
    private String currency = "INR";

    @Size(max = 500)
    private String remarks;

    /**
     * Demo-only: names a deterministic failure to inject downstream.
     *
     * <p>Null in normal operation, and no failure occurs unless it is set.
     * Present on the request rather than in configuration so a single payment
     * can be made to fail on demand without affecting any other -- which is
     * what makes the recovery path demonstrable rather than theoretical.
     *
     * <p>Accepted values: {@code TIMEOUT}, {@code SLOW}, {@code SERVER_ERROR},
     * {@code REJECT}. See docs/testing/failure-scenarios.md.
     */
    @Size(max = 40)
    private String simulate;
}
