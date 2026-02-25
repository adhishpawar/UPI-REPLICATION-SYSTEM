package com.upi.vpa_service.service.impl;

import com.upi.vpa_service.domain.dto.VpaRegistrationRequest;
import com.upi.vpa_service.domain.dto.VpaRegistrationResponse;
import com.upi.vpa_service.domain.dto.VpaResolutionResponse;
import com.upi.vpa_service.domain.entity.VpaRegistration;
import com.upi.vpa_service.exception.InvalidVpaFormatException;
import com.upi.vpa_service.exception.UnauthorizedVpaAccessException;
import com.upi.vpa_service.exception.VpaDuplicateException;
import com.upi.vpa_service.exception.VpaNotFoundException;
import com.upi.vpa_service.mapper.VpaMapper;
import com.upi.vpa_service.repository.VpaRepository;
import com.upi.vpa_service.service.VpaService;
import com.upi.vpa_service.util.VpaValidator;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j                      //For logging
@RequiredArgsConstructor   // preferred over @Autowired

public class VpaServiceImpl implements VpaService {

    @Autowired
    private  VpaRepository vpaRepository;
    @Autowired
    private  VpaMapper vpaMapper;
    @Autowired
    private VpaValidator vpaValidator;

    // Allowed PSP handles — stored in a HashSet for O(1) lookup
    private static final Set<String> ALLOWED_HANDLES = Set.of(
            "okaxis", "ybl", "paytm", "oksbi", "okicici",
            "upi", "okhdfcbank", "apl", "ibl");


    @Override
    @Transactional  //Wraps the entire method in a DB Transaction
    public VpaRegistrationResponse registerVpa(VpaRegistrationRequest request) {
        log.info("Registering VPA: {}", request.getVpaAddress());

        //1. validate VPA format
        vpaValidator.validateFormat(request.getVpaAddress());

        //2. validate PSP handle is allowed --> HashSet lookup
        String handle = extractHandle(request.getVpaAddress());
        if(!ALLOWED_HANDLES.contains(handle))
        {
            throw new InvalidVpaFormatException("Unknown PSP Handle: @" + handle);
        }

        //3. Check for the Duplicate -
        if(vpaRepository.existsByVpaAddress(request.getVpaAddress()))
        {
            throw new VpaDuplicateException(request.getVpaAddress());
        }

        //4. Map DTO --> Entity and Save
        VpaRegistration entity = vpaMapper.toEntity(request);
        entity.setPspHandle(handle);
        VpaRegistration saved = vpaRepository.save(entity);

        return vpaMapper.toRegistrationResponse(saved);
    }


    @Override
    @Transactional(readOnly = true) //No write optimize reads
    public VpaResolutionResponse resolveVpa(String vpaAddress) {
        return vpaRepository.findByVpaAddressAndIsActiveTrue(vpaAddress)
                .map(vpaMapper::toResolutionResponse)
                .orElseThrow(() -> new VpaNotFoundException(vpaAddress));
    }

    @Override
    @Transactional
    public void deactivateVpa(String vpaAddress, UUID requestingUserId) {
        VpaRegistration vpa = vpaRepository.findByVpaAddress(vpaAddress)
                .orElseThrow(() -> new VpaNotFoundException(vpaAddress));

        //Auth check --> only owner can deactivate
        if(!vpa.getUserId().equals(requestingUserId))
        {
            throw new UnauthorizedVpaAccessException(vpaAddress);
        }

        vpa.setIsActive(false);
        vpaRepository.save(vpa);
    log.info("VPA deactivated: {}", vpaAddress);
    }

    @Override
    public boolean isVpaActive(String vpaAddress)
    {
        return vpaRepository.existsByVpaAddressAndIsActiveTrue(vpaAddress);
    }

    @Override
    public List<VpaRegistrationResponse> getVpasByUserId(UUID userId) {
        List<VpaRegistration> vpaRegistrations = vpaRepository. findByUserId(userId);

        return vpaRegistrations.stream()
                .map(vpa -> vpaMapper.toRegistrationResponse(vpa))
                .collect(Collectors.toList());
    }


    //Helper
    private String extractHandle(String vpaAddress)
    {
        String[] parts = vpaAddress.split("@");
        return parts[parts.length - 1].toLowerCase();
    }
}
