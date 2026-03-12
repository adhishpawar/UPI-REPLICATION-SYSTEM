package com.upi.psp.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UserRegistrationRequest {

    @NotBlank(message = "mobile number required")
    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Invalid mobile number format")
    private String mobileNumber;

    @NotBlank(message = "Device ID required")
    @Size(min = 8, max = 255)
    private String deviceId;

    @NotBlank(message = "Device fingerprint is required")
    private String deviceFingerprint;   //raw --> hashed before storage



}
