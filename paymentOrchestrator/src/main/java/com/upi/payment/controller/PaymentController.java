package com.upi.payment.controller;

import com.upi.payment.domain.dto.*;
import com.upi.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;


import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Payment initiation and status APIs")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)  //202: request Accepted, processing async
    @Operation(summary = "Initiate a UPI payment")
    public PaymentInitiationResponse initiatePayment(
            @Valid @RequestBody PaymentInitiationRequest request,
            @RequestHeader("X-User-Id") UUID userId,    //Set by API GateWay
            @RequestHeader("X-Device-Id") String deviceId, //Set by API GateWay
            @RequestHeader("Idempotency-Key") String idempotencyKey
            ){

        log.info("Payment initiation: payer={} payee={} amount={} userId{}",
                request.getPayerVpa(), request.getPayeeVpa(),
                request.getAmount(), userId);

        return paymentService.initiatePayment(request, userId, deviceId, idempotencyKey);
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get full transaction detail with event audit trail")
    public TransactionDetailResponse getTransaction(
            @PathVariable UUID transactionId,
            @RequestHeader("X-User-Id") UUID userId) {
        return paymentService.getTransaction(transactionId, userId);
    }

    @GetMapping("/{transactionId}/status")
    @Operation(summary = "Lightweight status poll — just the current state")
    public TransactionStatusResponse getStatus(
            @PathVariable UUID transactionId,
            @RequestHeader("X-User-Id") UUID userId) {
        return paymentService.getStatus(transactionId, userId);
    }

    @GetMapping("/history")
    @Operation(summary = "Paginated payment history for the logged-in user")
    public Page<PaymentSummaryResponse> getHistory(
            @RequestHeader("X-User-Id") UUID userId,
            @PageableDefault(size = 20, sort = "initiatedAt") Pageable pageable) {
        return paymentService.getHistory(userId, pageable);
    }


}
