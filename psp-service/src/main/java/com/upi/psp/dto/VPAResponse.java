package com.upi.psp.dto;

import com.upi.psp.model.VPA;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VPAResponse {
    private String vpaId;
    private String userId;
    private String vpaAddress;
    private String pspId;
    private String bankAccountId;

    public static VPAResponse from(VPA vpa) {
        return new VPAResponse(
                vpa.getVpaId(),
                vpa.getUserId(),
                vpa.getVpaAddress(),
                vpa.getPspId(),
                vpa.getBankAccountId()
        );
    }
}
