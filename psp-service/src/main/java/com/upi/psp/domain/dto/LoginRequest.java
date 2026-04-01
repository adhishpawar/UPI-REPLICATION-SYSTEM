package com.upi.psp.domain.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class LoginRequest {

    @NotBlank @Pattern(regexp = "^[+]?[0-9]{10,15}$")
    private String mobileNumber;

    @NotBlank @Size(min = 8, max = 255)
    private String deviceId;

    @NotBlank @Pattern(regexp = "^[0-9]{4,6}$")
    @ToString.Exclude  // NEVER include MPIN in logs via toString()
    private String mpin;

}
