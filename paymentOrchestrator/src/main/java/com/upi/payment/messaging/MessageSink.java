package com.upi.payment.messaging;

import com.upi.payment.outbox.OutboxMessage;

/**
 * Where the relay delivers a message.
 *
 * <p>Two implementations exist, and the point of the interface is that they
 * are interchangeable:
 *
 * <ul>
 *   <li>{@code InProcessSink} -- calls handlers directly. Requires PostgreSQL
 *       and nothing else.</li>
 *   <li>{@code KafkaSink} -- publishes to a topic. Requires a broker.</li>
 * </ul>
 *
 * <p>Both give the same guarantee: at-least-once delivery, with ordering per
 * aggregate. The durability comes from the outbox table, not from the
 * transport -- which is the lesson. A broker does not give you atomicity with
 * your database, and no broker ever will.
 *
 * <p>Switching between them is a config change ({@code outbox.relay.sink}).
 * If that switch ever requires touching the saga, the boundary was drawn in
 * the wrong place.
 */
public interface MessageSink {

    /** Deliver, or throw. Throwing schedules a retry with backoff. */
    void deliver(OutboxMessage message);

    /** Matches {@code outbox.relay.sink}. */
    String name();
}
