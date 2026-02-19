package com.upi.npciswitch.model;
import jakarta.persistence.*;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "switch_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SwitchTransaction {

    @Id
    private String txId = UUID.randomUUID().toString();

    private String payerVPA;
    private String payeeVPA;
    private String payerBankAccount;
    private String payeeBankAccount;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
