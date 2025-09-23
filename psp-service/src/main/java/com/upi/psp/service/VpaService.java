package com.upi.psp.service;

import com.upi.psp.dto.*;
import com.upi.psp.model.VPA;
import com.upi.psp.repo.VpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class VpaService {
    private final VpaRepository vpaRepository;
    private final RestTemplate restTemplate;

    public VpaService(VpaRepository vpaRepository, RestTemplate restTemplate) {
        this.vpaRepository = vpaRepository;
        this.restTemplate = restTemplate;
    }

    private static final String BANK_SERVICE_URL = "http://localhost:8081"; // Bank service base

    public VPA createVPA(VPARequest req) {
        // Debug: Start of method execution
        System.out.println("Creating VPA for User ID: " + req.getUserId());

        // Fetch accounts from Bank Service
        System.out.println("Fetching accounts for user: " + req.getUserId());
        ResponseEntity<ApiResponse<List<BankAccountResponse>>> response =
                restTemplate.exchange(
                        BANK_SERVICE_URL + "/accounts/" + req.getUserId(),
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<ApiResponse<List<BankAccountResponse>>>() {}
                );

        List<BankAccountResponse> accounts = response.getBody().getData();

        // Debug: List of fetched accounts
        System.out.println("Fetched bank accounts: " + accounts);

        // Choose primary account
        BankAccountResponse primaryAccount = accounts.stream()
                .filter(BankAccountResponse::isPrimary)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No primary account found for user"));

        // Debug: Primary account chosen
        System.out.println("Primary account found: " + primaryAccount);

        // Auto-generate VPA (userName@upiHandle)
        String vpaAddress = req.getUserId().toString().substring(0, 6)
                + "@" + primaryAccount.getBankId().toString().substring(0, 4);

        // Debug: Generated VPA Address
        System.out.println("Generated VPA Address: " + vpaAddress);

        // Check if VPA already exists
        if (vpaRepository.existsByVpaAddress(vpaAddress)) {
            // Debug: VPA exists
            System.out.println("VPA already exists: " + vpaAddress);
            throw new RuntimeException("VPA already exists: " + vpaAddress);
        }

        // Create VPA object
        VPA vpa = new VPA();
        vpa.setVpaId(String.valueOf(System.currentTimeMillis())); // Updated to String

        // Debug: VPA ID
        System.out.println("Generated VPA ID: " + vpa.getVpaId());

        vpa.setUserId(req.getUserId());
        vpa.setBankAccountId(primaryAccount.getAccountId().toString());
        vpa.setPspId(req.getPspId());
        vpa.setVpaAddress(vpaAddress);
        vpa.setCreatedAt(Instant.now());

        // Debug: VPA details before saving
        System.out.println("VPA details to save: " + vpa);

        // Save VPA and return
        VPA savedVpa = vpaRepository.save(vpa);

        // Debug: VPA saved successfully
        System.out.println("VPA saved successfully: " + savedVpa);

        return savedVpa;
    }



    public List<VPAResponse> getVPAsByUser(String userId) {
        return vpaRepository.findByUserId(userId)
                .stream()
                .map(VPAResponse::from)
                .toList();
    }
}
