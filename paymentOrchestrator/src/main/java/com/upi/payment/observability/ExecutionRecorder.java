package com.upi.payment.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records what the platform is doing, as it does it.
 *
 * <p>Two properties are deliberate and worth understanding:
 *
 * <h3>1. Recording never fails a payment</h3>
 *
 * Every write runs in {@code REQUIRES_NEW} and every exception is swallowed
 * (and logged). Observability is not allowed to become a failure mode of the
 * thing it observes -- a payment must not fail because a trace row could not
 * be inserted.
 *
 * <p>This is the one place where swallowing an exception is correct, and it is
 * worth being precise about why. Rule 7 says never hide a failure. The failure
 * being hidden here is <em>the recording</em>, not the payment; it is logged at
 * WARN, and the payment's own success or failure is recorded by the state
 * machine and the audit trail regardless. Nothing about money becomes
 * invisible.
 *
 * <h3>2. Separate transaction, on purpose</h3>
 *
 * {@code REQUIRES_NEW} also means a trace row survives the rollback of the
 * business transaction that produced it. That is exactly what is wanted: when
 * a payment step fails and rolls back, the evidence of the attempt must
 * remain. A trace that disappears whenever something goes wrong is useless
 * precisely when it is needed.
 */
@Component
@Slf4j
public class ExecutionRecorder {

    public enum Kind {
        HTTP_IN,     // a request arrived
        HTTP_OUT,    // we called someone else
        DB,          // a database write worth showing
        STATE,       // a state machine transition
        EVENT_PUB,   // appended to the outbox
        EVENT_CONS,  // delivered/consumed
        RECOVERY,    // the self-healing subsystem acting
        LOG          // a narrative line
    }

    public enum Status { STARTED, OK, FAILED, TIMEOUT, SKIPPED }

    private final ExecutionEventRepository repository;
    private final ObjectMapper objectMapper;
    private final ExecutionEventBroadcaster broadcaster;

    /**
     * REQUIRES_NEW, applied explicitly rather than with {@code @Transactional}.
     *
     * <p>An annotation would not work here: Spring implements it with a proxy,
     * and a method calling a helper on {@code this} bypasses the proxy
     * entirely. The annotation would be silently ignored, the repository save
     * would join the caller's transaction, and every trace row would roll back
     * with the failure it was recording -- losing the evidence exactly when it
     * matters. This is explicit so it cannot quietly stop working.
     */
    private final TransactionTemplate newTx;

    public ExecutionRecorder(ExecutionEventRepository repository,
                             ObjectMapper objectMapper,
                             ExecutionEventBroadcaster broadcaster,
                             PlatformTransactionManager txManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Per-trace sequence counters.
     *
     * <p>Wall-clock time is not sufficient for ordering: two steps can land in
     * the same millisecond, and system clocks move backwards. A monotonic
     * counter per trace gives the showcase a stable order to render.
     *
     * <p>In-memory, so it resets on restart -- acceptable because a trace does
     * not outlive a request by much. For multi-instance deployments this would
     * move to a database sequence per trace.
     */
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();

    public void record(String traceId, UUID transactionId, String component,
                       String operation, Kind kind, Status status,
                       Integer latencyMs, String message, Object detail) {
        try {
            String trace = traceId != null ? traceId : MDC.get("traceId");
            if (trace == null) {
                trace = "untraced";
            }
            int seq = sequences.computeIfAbsent(trace, k -> new AtomicInteger())
                               .incrementAndGet();

            ExecutionEvent event = ExecutionEvent.builder()
                    .traceId(trace)
                    .transactionId(transactionId)
                    .seq(seq)
                    .component(component)
                    .operation(operation)
                    .kind(kind.name())
                    .status(status.name())
                    .latencyMs(latencyMs)
                    .message(truncate(message, 500))
                    .detail(detail == null ? null : writeJson(detail))
                    .build();

            ExecutionEvent saved = newTx.execute(txStatus -> repository.save(event));
            broadcaster.publish(saved);

        } catch (Exception ex) {
            // See the class comment: observing must not break the observed.
            log.warn("Failed to record execution event ({} {}): {}",
                    component, operation, ex.toString());
        }
    }

    /** Convenience for the common "step succeeded" case. */
    public void ok(UUID transactionId, String component, String operation,
                   Kind kind, String message) {
        record(null, transactionId, component, operation, kind, Status.OK, null, message, null);
    }

    /** Free the counter once a payment is finished. */
    public void closeTrace(String traceId) {
        if (traceId != null) {
            sequences.remove(traceId);
        }
    }

    private String writeJson(Object detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return "{\"serialisationError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
