package com.upi.bankservice.models;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

@Entity
@Table(name = "bank_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankAccount {

    @Id
    @Column(name = "account_id", nullable = false, unique = true, length = 20)
    private String accountId;

    @Column(name = "account_number", nullable = false, unique = true,length = 20)
    private String accountNumber;

    @Column(name = "user_id", nullable = false)
    private String userId; // comes from User-Service

    @Column(name = "bank_id", nullable = false)
    private String bankId; // maps to Bank

    @Column(name = "ifsc_code", nullable = false)
    private String ifscCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "is_primary")
    private boolean isPrimary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (this.accountId == null) {
            this.accountId = generateAccountId();
        }
        if (this.accountNumber == null) {
            this.accountNumber = generateAccountNumber();
        }
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.balance == null) {
            this.balance = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ---- Generators ----
    private String generateAccountId() {
        return "ACCID" + String.format("%010d", new Random().nextInt(1_000_000_000));
    }

    /**
     * Generate a 12-digit numeric account number.
     *
     * <p>Real Indian bank account numbers are numeric. The previous version
     * prefixed {@code "ANN"}, which made every account this service issued
     * unusable for VPA registration -- vpa-service validates
     * {@code ^[0-9]{9,18}$} and correctly rejected it.
     */
    private String generateAccountNumber() {
        Random r = new Random();
        return String.format("%06d", r.nextInt(1_000_000))
             + String.format("%06d", r.nextInt(1_000_000));
    }


    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getBankId() {
        return bankId;
    }

    public void setBankId(String bankId) {
        this.bankId = bankId;
    }

    public String getIfscCode() {
        return ifscCode;
    }

    public void setIfscCode(String ifscCode) {
        this.ifscCode = ifscCode;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public boolean isPrimary() {
        return isPrimary;
    }

    public void setPrimary(boolean primary) {
        isPrimary = primary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}