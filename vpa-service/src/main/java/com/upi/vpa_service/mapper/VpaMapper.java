package com.upi.vpa_service.mapper;

import com.upi.vpa_service.domain.dto.VpaRegistrationRequest;
import com.upi.vpa_service.domain.dto.VpaRegistrationResponse;
import com.upi.vpa_service.domain.dto.VpaResolutionResponse;
import com.upi.vpa_service.crypto.AccountNumberCipher;
import com.upi.vpa_service.domain.entity.VpaRegistration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VpaMapper {

    private final AccountNumberCipher cipher;

    public VpaRegistration toEntity(VpaRegistrationRequest request) {
        VpaRegistration entity = new VpaRegistration();
        entity.setVpaAddress(request.getVpaAddress().toLowerCase().trim());
        entity.setUserId(request.getUserId());
        entity.setAccountNumber(cipher.encrypt(request.getAccountNumber()));
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

    /**
     * Reverse the at-rest encryption.
     *
     * <p>Both methods used to live here and were Base64 in both directions,
     * despite being named encrypt/decrypt. The real implementation is in
     * {@link AccountNumberCipher}; this is kept as a thin delegate so callers
     * did not have to change.
     */
    public String decrypt(String encrypted) {
        return cipher.decrypt(encrypted);
    }


}
