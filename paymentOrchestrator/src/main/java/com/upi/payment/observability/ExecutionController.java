package com.upi.payment.observability;

import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.outbox.OutboxRelay;
import com.upi.payment.recovery.ReconciliationRecordRepository;
import com.upi.payment.recovery.RecoveryCase;
import com.upi.payment.recovery.RecoveryCaseRepository;
import com.upi.payment.statemachine.TransactionStateMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * Read-only endpoints exposing what the backend is doing.
 *
 * <p>This is the contract the backend showcase consumes, and the reason the
 * showcase can be honest: everything here is derived from rows the backend
 * wrote while doing real work. There is no endpoint that describes a payment
 * that did not happen.
 *
 * <p>The showcase is <b>not</b> privileged. It reads the same public API any
 * client could, holds no database credentials, and imports no backend class.
 * Delete it and nothing here changes; the rows are still written, the stream
 * still emits, and payments behave identically.
 */
@RestController
@RequestMapping("/api/v1/execution")
@RequiredArgsConstructor
public class ExecutionController {

    /** 5 minutes, after which the browser's EventSource reconnects on its own. */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ExecutionEventRepository executionEvents;
    private final ExecutionEventBroadcaster broadcaster;
    private final RecoveryCaseRepository recoveryCases;
    private final ReconciliationRecordRepository reconciliations;
    private final TransactionStateMachine stateMachine;
    private final OutboxRelay outboxRelay;

    /**
     * Live execution stream.
     *
     * <p>Server-Sent Events, not WebSocket: the traffic is one-directional, so
     * the extra machinery of a bidirectional channel would buy nothing. SSE is
     * plain HTTP and reconnects by itself.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe(SSE_TIMEOUT_MS);
    }

    /**
     * The complete trace for one payment, after the fact.
     *
     * <p>The persisted counterpart to the stream. A viewer who connected late,
     * refreshed, or was not watching at all loses nothing -- which is what
     * makes the stream a convenience over durable data rather than the data
     * itself.
     */
    @GetMapping("/{transactionId}")
    public List<Map<String, Object>> trace(@PathVariable UUID transactionId) {
        return executionEvents.findByTransactionIdOrderByIdAsc(transactionId)
                .stream().map(ExecutionController::toMap).toList();
    }

    /** The most recent activity across all payments. Used to seed the UI on load. */
    @GetMapping("/recent")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> events = executionEvents
                .findAllByOrderByIdDesc(PageRequest.of(0, Math.min(limit, 500)))
                .stream().map(ExecutionController::toMap).collect(java.util.stream.Collectors.toList());
        Collections.reverse(events);
        return events;
    }

    /**
     * The state machine as data.
     *
     * <p>Exposed so the showcase renders the graph the backend actually
     * enforces. A UI with its own hard-coded copy would drift the first time an
     * edge changed, and would then be confidently wrong about what the system
     * permits.
     */
    @GetMapping("/state-machine")
    public Map<String, Object> stateMachine() {
        Map<String, Object> graph = new LinkedHashMap<>();
        Map<String, List<String>> edges = new LinkedHashMap<>();
        List<String> terminal = new ArrayList<>();

        for (TransactionStatus s : TransactionStatus.values()) {
            edges.put(s.name(), stateMachine.getAllowedNextStates(s)
                    .stream().map(Enum::name).sorted().toList());
            if (stateMachine.isTerminal(s)) {
                terminal.add(s.name());
            }
        }
        graph.put("transitions", edges);
        graph.put("terminal", terminal);
        graph.put("afterMoneyMoved", Arrays.stream(TransactionStatus.values())
                .filter(TransactionStatus::isAfterMoneyMoved).map(Enum::name).toList());
        graph.put("uncertain", Arrays.stream(TransactionStatus.values())
                .filter(TransactionStatus::isUncertain).map(Enum::name).toList());
        return graph;
    }

    /** Recovery activity: what the self-healing subsystem has been doing. */
    @GetMapping("/recovery")
    public Map<String, Object> recovery() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openCases", recoveryCases.countByClosedAtIsNull());
        out.put("mismatches", reconciliations.countByMatchedFalse());
        out.put("recent", recoveryCases.findTop20ByOrderByDetectedAtDesc()
                .stream().map(ExecutionController::toMap).toList());
        return out;
    }

    /**
     * Backlog of committed-but-undelivered messages.
     *
     * <p>The single most useful operational number in an outbox system. Zero
     * and steady means the relay is keeping up. A climbing figure means
     * messages are committed and going nowhere -- state changes that the rest
     * of the system has not been told about yet.
     */
    @GetMapping("/outbox")
    public Map<String, Object> outbox() {
        return Map.of("pending", outboxRelay.pendingCount(),
                      "subscribers", broadcaster.subscriberCount());
    }

    private static Map<String, Object> toMap(ExecutionEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("traceId", e.getTraceId());
        m.put("transactionId", e.getTransactionId());
        m.put("seq", e.getSeq());
        m.put("component", e.getComponent());
        m.put("operation", e.getOperation());
        m.put("kind", e.getKind());
        m.put("status", e.getStatus());
        m.put("latencyMs", e.getLatencyMs());
        m.put("message", e.getMessage());
        m.put("at", String.valueOf(e.getAt()));
        return m;
    }

    private static Map<String, Object> toMap(RecoveryCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", c.getCaseId());
        m.put("transactionId", c.getTransactionId());
        m.put("detectedState", c.getDetectedState());
        m.put("classification", c.getClassification());
        m.put("strategy", c.getStrategy());
        m.put("attempts", c.getAttempts());
        m.put("outcome", c.getOutcome());
        m.put("note", c.getResolutionNote());
        m.put("detectedAt", String.valueOf(c.getDetectedAt()));
        m.put("closedAt", String.valueOf(c.getClosedAt()));
        return m;
    }
}
