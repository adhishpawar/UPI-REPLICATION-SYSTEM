package com.upi.psp.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Setter
@Getter
@Table(name = "payment_logs")
public class PaymentLog {
    @Id
    private String logId;

    private String txId;
    private String step;         // INITIATED, DEBIT_REQUEST, CREDIT_REQUEST, etc.
    private String details;      // JSON blob
    private Instant timestamp = Instant.now();

    @PrePersist
    public void generateLogId() {
        this.logId = generateLogIdMethod();
    }

    private String generateLogIdMethod() {
        String prefix = "LOG";  // Custom prefix
        UUID uuid = UUID.randomUUID();
        return prefix + uuid.toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}