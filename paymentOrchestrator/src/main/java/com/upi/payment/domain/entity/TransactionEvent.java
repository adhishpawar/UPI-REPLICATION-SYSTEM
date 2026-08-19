package com.upi.payment.domain.entity;

import com.upi.payment.domain.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transaction_events",
        indexes = {@Index(name="idx_events_txn_id", columnList="transaction_id, occurred_at")})
@Getter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class TransactionEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name="event_id", updatable=false, nullable=false)
    private UUID eventId;

    @Column(name="transaction_id", nullable=false, updatable=false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name="from_state", nullable=false, updatable=false, length=30)
    private TransactionStatus fromState;

    @Enumerated(EnumType.STRING)
    @Column(name="to_state", nullable=false, updatable=false, length=30)
    private TransactionStatus toState;

    @Column(name="description", length=500, updatable=false)
    private String description;

    @Column(name="event_payload", columnDefinition="JSONB")
    private String eventPayload;  // JSON string — flexible schema for bank responses

    @Column(name="triggered_by", length=100, updatable=false)
    private String triggeredBy;

    @Column(name="occurred_at", nullable=false, updatable=false)
    private LocalDateTime occurredAt;

    /**
     * @PreUpdate: JPA lifecycle callback invoked before any UPDATE.
     * This table is APPEND-ONLY. If anything tries to update an event,
     * this callback throws immediately, preventing the update.
     * This is the Java-level immutability guard (DB trigger is the second layer).
     */
    @PreUpdate
    public void preventUpdate() {
        throw new UnsupportedOperationException(
                "TransactionEvent is immutable. Use a new event for state changes.");
    }

}
