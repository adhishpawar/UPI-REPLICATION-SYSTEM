package com.upi.bankservice.models;


import jakarta.persistence.*;
import lombok.*;

import java.util.Random;

@Entity
@Table(name = "banks")
@Getter
@Setter
@AllArgsConstructor
@Builder
public class Bank {

    @Id
    @Column(name = "bank_id", nullable = false, unique = true, length = 32)
    private String bankId;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "ifsc_code", nullable = false, unique = true,length = 16)
    private String ifscCode;

    @Column(name = "upi_handle", nullable = false, unique = true)
    private String upiHandle; // e.g., @axis

    public Bank() {}

    public Bank(String name) {
        this.name = name;
        this.bankId = generateBankId();
        this.ifscCode = generateIfscCode(name);
    }

    /**
     * Generate a well-formed IFSC: {@code AAAA0BBBBBB}.
     *
     * <p>Four letters for the bank, a mandatory {@code 0} in the fifth
     * position, then six branch characters. The previous version emitted four
     * letters plus seven digits, which is the right length but the wrong shape
     * -- and vpa-service, which validates the real format, rejected every
     * account this service created. A cross-service format mismatch that only
     * surfaced when the two were finally wired together.
     */
    private String generateIfscCode(String bankName) {
        String letters = bankName.replaceAll("[^A-Za-z]", "").toUpperCase();
        String prefix = (letters + "XXXX").substring(0, 4);
        return prefix + "0" + String.format("%06d", new Random().nextInt(1_000_000));
    }

    private String generateBankId() {
        return "BANK" + String.format("%06d", new Random().nextInt(999999));
    }

    @PrePersist
    public void prePersist() {
        if (this.bankId == null) {
            this.bankId = generateBankId();
        }
        if (this.ifscCode == null) {
            this.ifscCode = generateIfscCode(this.name);
        }
    }

    public String getBankId() {
        return bankId;
    }

    public void setBankId(String bankId) {
        this.bankId = bankId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIfscCode() {
        return ifscCode;
    }

    public void setIfscCode(String ifscCode) {
        this.ifscCode = ifscCode;
    }

    public String getUpiHandle() {
        return upiHandle;
    }

    public void setUpiHandle(String upiHandle) {
        this.upiHandle = upiHandle;
    }
}