package com.upi.payment.ports;

import java.util.UUID;

/**
 * The platform's only way to emit a domain event.
 *
 * <p><b>Why this is a port and not a Kafka call.</b> The saga used to invoke
 * {@code kafkaTemplate.send()} directly, inside {@code @Transactional} and
 * before the commit. That is the dual-write bug: a database commit and a
 * broker publish are two independent systems and cannot be made atomic. If the
 * publish lands and the transaction then rolls back, a debit command is in
 * flight for a payment that does not exist.
 *
 * <p>Implementations of this interface must write the message into
 * <em>the caller's transaction</em>, so the state change and the intent to
 * send commit together or not at all. Delivery happens afterwards, from a
 * relay.
 *
 * <p><b>Guarantee: at-least-once.</b> The relay can crash after delivering and
 * before marking a message published, so consumers must be idempotent. There
 * is no exactly-once delivery across a system boundary; there is at-least-once
 * delivery plus idempotent processing, which is observationally the same
 * thing.
 *
 * @see com.upi.payment.outbox.OutboxEventPublisher
 */
public interface EventPublisher {

    /**
     * Record an event for delivery. Must be called inside an active
     * transaction -- the whole point is that it commits with the state change.
     *
     * @param aggregateType e.g. {@code "Transaction"}
     * @param aggregateId   the transaction id; also the ordering key
     * @param eventType     e.g. {@code "DebitRequested"}
     * @param payload       serialised to JSON
     * @param traceId       correlation id, carried end to end
     */
    void publish(String aggregateType, UUID aggregateId, String eventType,
                 Object payload, String traceId);
}
