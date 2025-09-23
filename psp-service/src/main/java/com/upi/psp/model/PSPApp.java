package com.upi.psp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "psp_apps")
public class PSPApp {
    @Id
    private String pspId;

    private String name;         // e.g., PhonePe, Paytm
    private String upiHandle;    // e.g., @paytm, @gpay

    @PrePersist
    public void generatePspId() {
        this.pspId = generatePspIdMethod();
    }

    private String generatePspIdMethod() {
        String prefix = "PSP";  // Custom prefix
        UUID uuid = UUID.randomUUID();
        return prefix + uuid.toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
