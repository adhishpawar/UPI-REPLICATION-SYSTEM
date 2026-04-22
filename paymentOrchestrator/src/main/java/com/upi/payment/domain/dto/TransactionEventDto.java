package com.upi.payment.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class TransactionEventDto {
    private String fromState;
    private String toState;
    private String description;
    private LocalDateTime occurredAt;
    private String triggeredBy;
}

