package com.upi.vpa_service.mapper;

import com.upi.vpa_service.domain.dto.VpaRegistrationRequest;
import com.upi.vpa_service.domain.dto.VpaRegistrationResponse;
import com.upi.vpa_service.domain.dto.VpaResolutionResponse;
import com.upi.vpa_service.domain.entity.VpaRegistration;

import java.util.Base64;


public class VpaMapper {

    public VpaRegistration toEntity(VpaRegistrationRequest request) {
        VpaRegistration entity = new VpaRegistration();
        entity.setVpaAddress(request.getVpaAddress().toLowerCase().trim());
        entity.setUserId(request.getUserId());
        entity.setAccountNumber(encrypt(request.getAccountNumber())); // Encrypt before saving
        entity.setIfscCode(request.getIfscCode().toUpperCase());
        entity.setAccountHolderName(request.getAccountHolderName().trim());
        entity.setIsActive(true);
        return entity;
    }

    public VpaRegistrationResponse toRegistrationResponse(VpaRegistration entity) {
        return VpaRegistrationResponse.builder()
                .vpaId(entity.getVpaId())
                .vpaAddress(entity.getVpaAddress())
                .accountHolderName(entity.getAccountHolderName())
                .pspHandle(entity.getPspHandle())
                .isActive(entity.getIsActive())
                .createdAt(entity.getCreatedAt())
                .build();
        // accountNumber NOT included in response — security rule
    }

    public VpaResolutionResponse toResolutionResponse(VpaRegistration entity) {
        return VpaResolutionResponse.builder()
                .vpaAddress(entity.getVpaAddress())
                .accountHolderName(entity.getAccountHolderName())
                .pspHandle(entity.getPspHandle())
                .isActive(entity.getIsActive())
                .build();
    }

    // For Production: replace with AES-256 encryption using Java Cipher class

    private String encrypt(String accountNumber) {
        return Base64.getEncoder().encodeToString(accountNumber.getBytes());
    }

    public String decrypt(String encrypted) {
        return new String(Base64.getDecoder().decode(encrypted));
    }


}
