package com.upi.psp.controller;

import com.upi.psp.dto.*;
import com.upi.psp.model.VPA;
import com.upi.psp.service.PaymentService;
import com.upi.psp.service.VpaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/psp")
@RequiredArgsConstructor
public class PSPController {

    private final VpaService vpaService;
    private final PaymentService paymentService;

    // ----------------- VPA Management -----------------

    // POST /psp/vpa → create a new VPA
    @PostMapping("/vpa")
    public ResponseEntity<ApiResponse<VPAResponse>> createVPA(@RequestBody VPARequest request) {
        VPA vpa = vpaService.createVPA(request);
        return ResponseEntity.ok(new ApiResponse<>("SUCCESS", "VPA created", VPAResponse.from(vpa)));
    }

    // GET /psp/vpa/{userId} → get all VPAs of a user
    @GetMapping("/vpa/{userId}")
    public ResponseEntity<ApiResponse<List<VPAResponse>>> getVPAs(@PathVariable String userId) {
        List<VPAResponse> list = vpaService.getVPAsByUser(userId);
        return ResponseEntity.ok(new ApiResponse<>("SUCCESS", "Fetched VPAs", list));
    }

    // ----------------- Payment Flow -----------------

    // Step 1: Initiate payment (payer PSP → NPCI)
    @PostMapping("/payments/initiate")
    public ResponseEntity<ApiResponse<PaymentInitResponse>> initiatePayment(@RequestBody PaymentInitRequest request) {
        PaymentInitResponse response = paymentService.initiatePayment(request);
        return ResponseEntity.ok(new ApiResponse<>("SUCCESS", "Payment initiated", response));
    }

    // Step 2: Validate payee details (NPCI → payee PSP)
    @PostMapping("/payments/validate")
    public ResponseEntity<ApiResponse<PayeeValidationResponse>> validatePayee(@RequestBody PayeeValidationRequest request) {
        PayeeValidationResponse response = paymentService.validatePayee(request);
        return ResponseEntity.ok(new ApiResponse<>("SUCCESS", "Payee validated", response));
    }

    // Step 3: Debit payer account (via BankService)
    @PutMapping("/{txId}/debit/{accountNumber}")
    public ResponseEntity<ApiResponse<TransactionResponse>> debitAccount(
            @PathVariable String txId,
            @PathVariable String accountNumber,
            @RequestBody TransactionRequest request) {

        TransactionResponse response = paymentService.debit(txId, accountNumber, request.getAmount());
        return ResponseEntity.ok(new ApiResponse<>("SUCCESS", "Debit successful", response));
    }

    // Step 4: Credit payee account (via BankService)
    @PutMapping("/{txId}/credit/{accountNumber}")
    public ResponseEntity<ApiResponse<TransactionResponse>> creditAccount(
            @PathVariable String txId,
            @PathVariable String accountNumber,
            @RequestBody TransactionRequest request) {

        TransactionResponse response = paymentService.credit(txId, accountNumber, request.getAmount());
        return ResponseEntity.ok(new ApiResponse<>("SUCCESS", "Credit successful", response));
    }
}

