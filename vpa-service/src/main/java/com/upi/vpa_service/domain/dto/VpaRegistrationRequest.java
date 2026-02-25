package com.upi.vpa_service.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.UUID;

@Getter
@Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VpaRegistrationRequest {
    @NotBlank(message = "VPA address is required")
    @Pattern(
            regexp = "^[a-z0-9._]{3,50}@[a-z]{3,20}$",
            message = "VPA format must be localpart@handle. Example: rahul@okaxis"
    )
    private String vpaAddress;

    @NotNull(message = "User ID is required")
    private UUID userId;

    @NotBlank(message = "Account number is required")
    @Size(min = 9, max = 18, message = "Account number must be 9-18 digits")
    @Pattern(regexp = "^[0-9]+$", message = "Account number must be numeric")
    private String accountNumber;

    @NotBlank(message = "IFSC code is required")
    @Pattern(regexp = "^[A-Z]{4}0[A-Z0-9]{6}$", message = "Invalid IFSC format. Example: HDFC0001234")
    private String ifscCode;

    @NotBlank(message = "Account holder name is required")
    @Size(min = 2, max = 200)
    private String accountHolderName;

}
