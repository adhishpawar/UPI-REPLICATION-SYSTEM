package com.upi.payment.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Proof that a given consumer has already handled a given message.
 *
 * <p>This is the other half of at-least-once delivery. The relay guarantees a
 * message arrives <em>at least</em> once; this table makes the second arrival
 * harmless. Written in the same transaction as the consumer's side effect, so
 * "the debit happened" and "we recorded that we did it" cannot come apart.
 *
 * <p>Composite key on (message, consumer) rather than message alone: several
 * consumers may legitimately need the same event, and each must deduplicate
 * independently.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.Key.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProcessedEvent {

    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Id
    @Column(name = "consumer", nullable = false, length = 100)
    private String consumer;

    @Column(name = "processed_at", nullable = false)
    @Builder.Default
    private LocalDateTime processedAt = LocalDateTime.now();

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID messageId;
        private String consumer;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(messageId, k.messageId)
                && Objects.equals(consumer, k.consumer);
        }
        @Override public int hashCode() { return Objects.hash(messageId, consumer); }
    }
}
