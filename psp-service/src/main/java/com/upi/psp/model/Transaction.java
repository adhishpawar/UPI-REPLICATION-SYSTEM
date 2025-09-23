package com.upi.psp.model;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "transactions")
public class Transaction {
    @Id
    private String txId;

    private String fromVpa;     // payer VPA
    private String toVpa;       // payee VPA
    private double amount;
    private String currency;    // e.g., INR
    private String status;      // INITIATED, PENDING, SUCCESS, FAILED
    private String failureCode; // e.g., INSUFFICIENT_FUNDS, ACCOUNT_CLOSED
    private String npciTxnId;   // reference from NPCI
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PrePersist
    public void generateTxId() {
        this.txId = generateTransactionId();
    }

    private String generateTransactionId() {
        String prefix = "TXN";  // Custom prefix
        UUID uuid = UUID.randomUUID();
        return prefix + uuid.toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
