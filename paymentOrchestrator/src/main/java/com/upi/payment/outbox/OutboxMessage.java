package com.upi.payment.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A message that has been committed but not yet delivered.
 *
 * <p>The row exists because a state change happened. It is written in the same
 * transaction as that state change, which is the entire mechanism: there is no
 * moment at which the state is durable and the message is not, and no moment
 * at which the message escapes and the state is not.
 */
@Entity
@Table(name = "outbox_messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "message_id", updatable = false, nullable = false)
    private UUID messageId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    /** Also the ordering/partition key: all events for one payment stay ordered. */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "JSONB")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String payload;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** NULL means still owed. This is the only field the relay flips on success. */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    @Builder.Default
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    /**
     * Out of attempts. The message stays in the table forever -- a dead letter
     * is evidence, not garbage. Something is wrong and a human needs to see it;
     * deleting it would hide the failure, which Rule 7 forbids.
     */
    @Column(name = "dead_lettered", nullable = false)
    @Builder.Default
    private Boolean deadLettered = false;
}
