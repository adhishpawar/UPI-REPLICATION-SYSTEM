package com.upi.vpa_service.service;

import com.upi.vpa_service.domain.dto.VpaRegistrationRequest;
import com.upi.vpa_service.domain.dto.VpaRegistrationResponse;
import com.upi.vpa_service.domain.dto.VpaResolutionResponse;

import java.util.*;

public interface VpaService {

    //Throws VapDuplicationException if VPA already present
    VpaRegistrationResponse registerVpa(VpaRegistrationRequest request);

    //Resolve a VPA Address to account Holder details
    //called by payment Orchestrator before evey payment
    //Throws VpaNotFoundException if VPA does not exist or is inactive
    VpaResolutionResponse resolveVpa(String vpaAddress);

    void deactivateVpa(String vpaAddress, UUID requestingUserId);   //Soft delete

    boolean isVpaActive(String vpaAddress); //Light weight boolean check

    List<VpaRegistrationResponse> getVpasByUserId(UUID userId);   //Get all the VPA registered to the User

}
