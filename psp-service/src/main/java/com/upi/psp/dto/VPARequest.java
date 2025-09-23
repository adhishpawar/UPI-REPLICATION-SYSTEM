package com.upi.psp.dto;

import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VPARequest {
    private String userId;
    private String pspId;
    private String bankAccountId;
}
