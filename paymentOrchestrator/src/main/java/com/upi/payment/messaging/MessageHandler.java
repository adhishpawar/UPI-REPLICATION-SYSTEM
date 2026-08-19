package com.upi.payment.messaging;

import com.upi.payment.outbox.OutboxMessage;

import java.util.Set;

/**
 * A consumer of one domain event type.
 *
 * <p>Implementations are ordinary Spring beans. The relay discovers them and
 * routes by {@link #eventType()}. Whether the message travelled through an
 * in-process call or through a Kafka topic is invisible here -- which is the
 * test of whether the messaging boundary was drawn correctly.
 *
 * <p><b>Every implementation must be idempotent.</b> Delivery is at-least-once
 * and always will be: the relay can crash between delivering a message and
 * recording that it delivered it. Extend {@link IdempotentMessageHandler}
 * rather than implementing the deduplication by hand.
 */
public interface MessageHandler {

    /** The event types this handler consumes, e.g. {@code "DebitRequested"}. */
    Set<String> eventTypes();

    /** Consumer identity. Used as half of the deduplication key. */
    String consumerName();

    /**
     * Process the message.
     *
     * <p>Throwing signals failure and causes redelivery with backoff. Do not
     * catch-and-continue: a swallowed exception marks the message delivered
     * and the work silently never happens.
     */
    void handle(OutboxMessage message);
}
