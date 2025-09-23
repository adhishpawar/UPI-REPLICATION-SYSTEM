package com.upi.psp.model;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "vpas")
public class VPA {
    @Id
    private String vpaId;

    private String userId;       // maps to AuthService user
    private String vpaAddress;   // e.g. user@psp
    private String pspId;        // PSPApp reference
    private String bankAccountId;
    private Instant createdAt = Instant.now();

    @PrePersist
    public void generateVpaId() {
        this.vpaId = generateVpaIdMethod();
    }

    private String generateVpaIdMethod() {
        String prefix = "VPA";  // Custom prefix
        UUID uuid = UUID.randomUUID();
        return prefix + uuid.toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}

