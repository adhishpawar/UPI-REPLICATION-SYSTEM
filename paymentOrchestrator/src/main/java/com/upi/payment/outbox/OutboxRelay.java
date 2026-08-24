package com.upi.payment.outbox;

import com.upi.payment.messaging.MessageSink;
import com.upi.payment.observability.ExecutionRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Drains the outbox.
 *
 * <p>This is the second half of the transactional outbox. The first half
 * guarantees a message exists if and only if the state change that produced it
 * committed. This half guarantees every such message is eventually delivered.
 *
 * <h3>Why delivery is at-least-once, and cannot be otherwise</h3>
 *
 * The relay does two things that are not atomic with each other: it hands the
 * message to a sink, and it marks the row published. A crash in between means
 * the message was delivered while the row still says it was not, so the next
 * sweep delivers it again.
 *
 * <p>The ordering could be inverted -- mark first, then deliver -- and then a
 * crash in between loses the message entirely, with nothing left to indicate
 * anything is missing. For money, a duplicate a consumer can detect is
 * strictly better than a loss nobody can detect. So: <b>deliver, then
 * mark</b>, and require every consumer to be idempotent.
 *
 * <p>That is the entire trade. No third option makes delivery exactly-once
 * across a process boundary. Exactly-once <em>processing</em> is achievable,
 * and this pairing is how it is achieved.
 *
 * <h3>A note on transactions and self-invocation</h3>
 *
 * The transactional units below use {@link TransactionTemplate} rather than
 * {@code @Transactional}. Spring implements {@code @Transactional} with a
 * proxy, so a method calling another method on {@code this} bypasses it
 * entirely -- the annotation is silently ignored. A scheduled method calling
 * its own helpers is exactly that shape, and the bug is invisible: the code
 * looks transactional and is not. Being explicit removes the trap.
 */
@Component
@Slf4j
public class OutboxRelay {

    /**
     * How long a claimed batch is reserved for. Pushing {@code next_attempt_at}
     * forward at claim time is a lease: a second instance will not pick up
     * these rows while the first is working on them, and if the first dies the
     * lease simply expires. An in-memory flag could not survive a crash.
     */
    private static final int CLAIM_LEASE_SECONDS = 30;

    private final OutboxRepository outboxRepository;
    private final MessageSink sink;
    private final ExecutionRecorder recorder;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxRepository outboxRepository,
                       List<MessageSink> sinks,
                       ExecutionRecorder recorder,
                       TransactionTemplate transactionTemplate,
                       @Value("${outbox.relay.sink:in-process}") String sinkName,
                       @Value("${outbox.relay.batch-size:50}") int batchSize,
                       @Value("${outbox.relay.max-attempts:10}") int maxAttempts) {

        this.outboxRepository = outboxRepository;
        this.recorder = recorder;
        this.tx = transactionTemplate;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sink = sinks.stream()
                .filter(s -> s.name().equals(sinkName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No MessageSink named '" + sinkName + "'. Available: "
                                + sinks.stream().map(MessageSink::name).toList()));

        log.info("OutboxRelay using sink '{}' (batch={}, maxAttempts={})",
                sinkName, batchSize, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:200}")
    public void drain() {
        List<OutboxMessage> batch = claim();
        for (OutboxMessage message : batch) {
            deliverOne(message);
        }
    }

    /**
     * Claim a batch in one short transaction.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} means a second instance takes
     * different rows rather than blocking on these. The lease bump then keeps
     * them reserved after this transaction commits and releases the row locks.
     */
    private List<OutboxMessage> claim() {
        return tx.execute(status -> {
            List<OutboxMessage> batch = outboxRepository.claimBatch(
                    LocalDateTime.now(), PageRequest.of(0, batchSize));

            LocalDateTime lease = LocalDateTime.now().plusSeconds(CLAIM_LEASE_SECONDS);
            for (OutboxMessage m : batch) {
                m.setNextAttemptAt(lease);
            }
            return batch;
        });
    }

    /**
     * Deliver one message, then record the outcome.
     *
     * <p>Deliberately not wrapped in a single transaction: delivery is an
     * external effect that no rollback can undo. Wrapping it would create the
     * illusion of atomicity across a boundary that has none.
     */
    private void deliverOne(OutboxMessage message) {
        long start = System.nanoTime();
        try {
            sink.deliver(message);
            tx.executeWithoutResult(s -> markPublished(message.getMessageId()));

            recorder.record(message.getTraceId(), message.getAggregateId(),
                    "outbox-relay", "deliver " + message.getEventType(),
                    ExecutionRecorder.Kind.EVENT_CONS, ExecutionRecorder.Status.OK,
                    msSince(start), "delivered via " + sink.name(), null);

        } catch (Exception ex) {
            log.warn("Outbox delivery failed id={} type={} attempt={}: {}",
                    message.getMessageId(), message.getEventType(),
                    message.getAttempts() + 1, ex.toString());

            tx.executeWithoutResult(s -> recordFailure(message.getMessageId(), ex.toString()));

            recorder.record(message.getTraceId(), message.getAggregateId(),
                    "outbox-relay", "deliver " + message.getEventType(),
                    ExecutionRecorder.Kind.EVENT_CONS, ExecutionRecorder.Status.FAILED,
                    msSince(start), truncate(ex.getMessage(), 480), null);
        }
    }

    private void markPublished(UUID messageId) {
        outboxRepository.findById(messageId).ifPresent(m -> {
            m.setPublishedAt(LocalDateTime.now());
            m.setAttempts(m.getAttempts() + 1);
            outboxRepository.save(m);
        });
    }

    private void recordFailure(UUID messageId, String error) {
        outboxRepository.findById(messageId).ifPresent(m -> {
            int attempts = m.getAttempts() + 1;
            m.setAttempts(attempts);
            m.setLastError(truncate(error, 1000));

            if (attempts >= maxAttempts) {
                // Kept, never deleted. A dead letter is evidence that
                // something needs attention; deleting it would hide a failure.
                m.setDeadLettered(true);
                log.error("Outbox message DEAD-LETTERED after {} attempts: id={} type={} txn={}",
                        attempts, m.getMessageId(), m.getEventType(), m.getAggregateId());
            } else {
                // Exponential backoff, capped at 30s. Retrying a struggling
                // dependency at full speed is how degradation becomes outage.
                long backoffMs = Math.min(30_000L, (long) (200L * Math.pow(2, attempts)));
                m.setNextAttemptAt(LocalDateTime.now().plusNanos(backoffMs * 1_000_000L));
            }
            outboxRepository.save(m);
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private int msSince(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    /** Exposed for health reporting and the showcase. */
    public long pendingCount() {
        return outboxRepository.countByPublishedAtIsNullAndDeadLetteredFalse();
    }
}
