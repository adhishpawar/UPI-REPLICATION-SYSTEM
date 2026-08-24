package com.upi.payment.controller;

import com.upi.payment.domain.dto.*;
import com.upi.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The payment API.
 *
 * <h3>Where identity comes from</h3>
 *
 * The authenticated user is read from the <b>verified JWT</b>, never from a
 * request header. Previously every method took {@code @RequestHeader
 *("X-User-Id")}, described as being set by an API gateway that did not exist —
 * so a caller simply asserted who they were, on an API that moves money.
 *
 * <p>The subject claim cannot be forged without psp-service's private key, and
 * the signature is checked before this class is reached. The difference is not
 * cosmetic: it is the difference between a claim and a proof.
 *
 * <h3>Why 202 and not 200</h3>
 *
 * Initiation returns {@code 202 Accepted}: the payment has been recorded and
 * will be processed, but the money has not moved yet. Returning 200 would
 * imply it had. Clients poll {@code /status} or watch the execution stream.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Payment initiation and status APIs")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Initiate a UPI payment")
    public PaymentInitiationResponse initiatePayment(
            @Valid @RequestBody PaymentInitiationRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        UUID userId = userIdOf(jwt);
        String deviceId = deviceIdOf(jwt);

        log.info("Payment initiation: payer={} payee={} amount={} user={}",
                request.getPayerVpa(), request.getPayeeVpa(), request.getAmount(), userId);

        return paymentService.initiatePayment(request, userId, deviceId, idempotencyKey);
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get full transaction detail with event audit trail")
    public TransactionDetailResponse getTransaction(
            @PathVariable UUID transactionId,
            @AuthenticationPrincipal Jwt jwt) {
        return paymentService.getTransaction(transactionId, userIdOf(jwt));
    }

    @GetMapping("/{transactionId}/status")
    @Operation(summary = "Lightweight status poll")
    public TransactionStatusResponse getStatus(
            @PathVariable UUID transactionId,
            @AuthenticationPrincipal Jwt jwt) {
        return paymentService.getStatus(transactionId, userIdOf(jwt));
    }

    @GetMapping("/history")
    @Operation(summary = "Paginated payment history for the authenticated user")
    public Page<PaymentSummaryResponse> getHistory(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20, sort = "initiatedAt") Pageable pageable) {
        return paymentService.getHistory(userIdOf(jwt), pageable);
    }

    /**
     * The user id, from the token's subject.
     *
     * <p>psp-service sets {@code sub} to the user's UUID. A token that reaches
     * here has already had its signature, expiry and issuer verified, so this
     * value is trustworthy in a way the old header never was.
     */
    private UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * The device the token was issued to.
     *
     * <p>Taken from the token rather than a header so it cannot be swapped for
     * another device's after the fact — the binding between "this session" and
     * "this device" is signed.
     */
    private String deviceIdOf(Jwt jwt) {
        String deviceId = jwt.getClaimAsString("deviceId");
        return deviceId != null ? deviceId : "unknown-device";
    }
}
