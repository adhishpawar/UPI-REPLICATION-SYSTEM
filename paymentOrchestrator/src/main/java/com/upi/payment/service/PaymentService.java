package com.upi.payment.service;

import com.upi.payment.domain.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PaymentService {

    PaymentInitiationResponse initiatePayment(
            PaymentInitiationRequest request, UUID userId,
            String deviceId, String idempotencyKey
    );

    TransactionDetailResponse getTransaction(UUID transactionId, UUID userId);

    TransactionStatusResponse getStatus(UUID transactionId, UUID userId);

    Page<PaymentSummaryResponse> getHistory(UUID userId, Pageable pageable);

}
