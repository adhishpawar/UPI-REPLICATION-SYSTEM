package com.upi.payment.observability;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One step the platform took while processing a payment.
 *
 * <p>Rows here answer "what actually happened, in what order, and how long did
 * each part take" -- for an operator after an incident, and for the showcase
 * in real time. Those turn out to be the same question, so one mechanism
 * serves both.
 *
 * <p>Because these rows are written by the backend while doing real work, a UI
 * that renders them cannot invent activity. Delete the showcase and the rows
 * are still here; stop the backend and the showcase has nothing to draw.
 */
@Entity
@Table(name = "execution_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExecutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    /** Position within the trace. Wall-clock timestamps are not enough: two
     *  steps can share a millisecond, and clocks are not monotonic. */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "component", nullable = false, length = 60)
    private String component;

    @Column(name = "operation", nullable = false, length = 120)
    private String operation;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "detail", columnDefinition = "JSONB")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String detail;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "at", nullable = false)
    @Builder.Default
    private LocalDateTime at = LocalDateTime.now();
}
