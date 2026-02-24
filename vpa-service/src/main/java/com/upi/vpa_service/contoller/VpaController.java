package com.upi.vpa_service.contoller;


import com.upi.vpa_service.domain.dto.VpaRegistrationRequest;
import com.upi.vpa_service.domain.dto.VpaRegistrationResponse;
import com.upi.vpa_service.domain.dto.VpaResolutionResponse;
import com.upi.vpa_service.service.VpaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vpa")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "VPA service", description = "Virtual payment Address Management APIs")
public class VpaController {

    private final VpaService vpaService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary =  " Register a new VPA")
    public VpaRegistrationResponse registerVpa(@Valid @RequestBody VpaRegistrationRequest request)
    {
        log.info("VPA reg request for: {} ", request.getVpaAddress());
        return vpaService.registerVpa(request);
    }

    @GetMapping("/{vpaAddress}")
    @Operation(summary = "Resolve a VPA to acount holder details")
    public VpaResolutionResponse resolveVpa(@PathVariable String vpaAddress)
    {
        return vpaService.resolveVpa(vpaAddress.toLowerCase().trim());
    }

    @DeleteMapping("/vpaAddress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate A VPA (soft delete)")
    public void deactivateVpa(@PathVariable String vpaAddress, @RequestHeader("X-user-Id") UUID requestingUserId)
    {
        //X-User-Id Header injected by API gateway from the JWT Claim
        vpaService.deactivateVpa(vpaAddress, requestingUserId);
    }

    @GetMapping("/{vpaAddress}/status")
    @Operation(summary = "check if a VPA is Active")
    public Map<String, Boolean> checkStatus(@PathVariable String vpaAddress)
    {
        return Map.of("active", vpaService.isVpaActive(vpaAddress));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get All VPAs for a User")
    public List<VpaRegistrationResponse> getByUser(@PathVariable UUID userId)
    {
        return vpaService.getVpasByUserId(userId);
    }
}
