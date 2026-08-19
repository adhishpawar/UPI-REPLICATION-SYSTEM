package com.upi.payment.messaging;

import com.upi.payment.outbox.OutboxMessage;
import com.upi.payment.outbox.ProcessedEvent;
import com.upi.payment.outbox.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base class that makes a handler safe to call twice.
 *
 * <h3>The shape that matters</h3>
 *
 * Deduplication and the side effect happen in <b>one transaction</b>:
 *
 * <pre>
 *   BEGIN
 *     if already processed -> return          (drop the duplicate)
 *     do the work                              (move money, change state)
 *     record that we processed it
 *   COMMIT
 * </pre>
 *
 * <p>If those were separate transactions, a crash in between would leave a
 * debit performed but not recorded as processed, and the next redelivery would
 * perform it again. Atomicity is what makes the deduplication honest.
 *
 * <p>The {@code existsBy} check is a fast path, not the guarantee. Two
 * concurrent deliveries of the same message both read "not processed" and both
 * proceed; the loser then violates the primary key on
 * {@code processed_events}, which is caught and treated as "someone else did
 * it". The constraint is the real guard.
 *
 * <h3>Why the transaction is explicit</h3>
 *
 * This class originally carried {@code @Transactional} on a {@code final}
 * {@code handle()} method with {@code @Autowired} fields, and it failed on the
 * first real payment with a {@link NullPointerException} on
 * {@code processedEvents}. The mechanism is worth knowing, because the symptom
 * points nowhere near the cause:
 *
 * <ol>
 *   <li>{@code @Transactional} makes Spring wrap the bean in a CGLIB proxy --
 *       a generated subclass.</li>
 *   <li>The proxy instance is allocated <b>without running any constructor</b>
 *       and without field injection. Its own fields are all null. That is
 *       normally invisible, because every intercepted call is delegated to the
 *       real target object, which is fully populated.</li>
 *   <li>CGLIB cannot override a {@code final} method. So {@code handle()} was
 *       not intercepted, ran directly on the proxy instance, and saw its null
 *       fields.</li>
 * </ol>
 *
 * <p>Two independently reasonable choices -- "make the template method final so
 * subclasses cannot skip deduplication" and "annotate it transactional" --
 * combine into a failure. Managing the transaction explicitly removes the
 * proxy, so {@code handle()} can stay {@code final} and the fields are always
 * there.
 */
@Slf4j
public abstract class IdempotentMessageHandler implements MessageHandler {

    private ProcessedEventRepository processedEvents;
    private TransactionTemplate tx;

    /**
     * Setter injection rather than a constructor parameter, so that every
     * subclass does not have to thread these two dependencies through its own
     * constructor purely to hand them back to this class.
     */
    @Autowired
    public final void setIdempotencyInfrastructure(ProcessedEventRepository processedEvents,
                                                   PlatformTransactionManager txManager) {
        this.processedEvents = processedEvents;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public final void handle(OutboxMessage message) {
        tx.executeWithoutResult(status -> {
            if (processedEvents.existsByMessageIdAndConsumer(
                    message.getMessageId(), consumerName())) {
                log.debug("Duplicate message dropped: id={} consumer={}",
                        message.getMessageId(), consumerName());
                return;
            }

            doHandle(message);

            try {
                processedEvents.save(ProcessedEvent.builder()
                        .messageId(message.getMessageId())
                        .consumer(consumerName())
                        .build());
            } catch (DataIntegrityViolationException dup) {
                // A concurrent delivery of the same message won the race. Our
                // work rolls back with this transaction; theirs stands. Exactly
                // one execution survives, which is the required outcome.
                log.debug("Concurrent duplicate for message {} consumer {}",
                        message.getMessageId(), consumerName());
                throw dup;
            }
        });
    }

    /** The actual work. Runs inside the transaction opened by {@link #handle}. */
    protected abstract void doHandle(OutboxMessage message);
}
