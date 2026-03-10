package com.upi.psp.domain.dto;


import com.upi.psp.domain.enums.UserStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class UserRegistrationResponse {

    private UUID userId;

    //Device id echoed back for client verification
    private String deviceId;

    /**
     * Current account status. Will be PENDING_MPIN immediately after registration.
     * Client should display "Please set your MPIN to continue."
     */
    private UserStatus status;

    /*
    Masked mobile number for confirmation display
    eg *****9007 -->neven return the full number
    **/
    private String maskedMobile;

    private LocalDateTime createdAt;

    private String message;

}
