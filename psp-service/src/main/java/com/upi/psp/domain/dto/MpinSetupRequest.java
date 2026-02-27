package com.upi.psp.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class MpinSetupRequest {

    @NotNull(message = "User ID required")
    private UUID userId;

    @NotBlank(message = "MPIN required")
    @Pattern(regexp = "^[0-9]{4,6}$",
            message = "MPIN must be 4-6 digits")
    // Note: @ToString.Exclude prevents Lombok from including MPIN in toString()
    @ToString.Exclude
    private String mpin;


}
