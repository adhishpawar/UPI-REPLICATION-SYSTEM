package com.upi.payment.messaging;

import com.upi.payment.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Delivers outbox messages to Kafka.
 *
 * <p>Selected with {@code outbox.relay.sink: kafka}. Not active by default,
 * because no broker is running in this environment. It exists so the claim in
 * ADR-0001 is verifiable rather than aspirational: the transport is genuinely
 * swappable, and nothing in the saga, the handlers, or the state machine
 * changes when it is swapped.
 *
 * <p>Two details that are easy to get wrong:
 *
 * <ul>
 *   <li>The send is <b>waited on</b>. {@code KafkaTemplate.send()} returns a
 *       future; the previous code attached a callback that logged failures and
 *       moved on, so a dropped publish was invisible beyond a log line. Here a
 *       failed send throws, and the relay retries the row it never marked
 *       published.</li>
 *   <li>The partition key is the aggregate id. Kafka guarantees ordering
 *       within a partition only, so all events for one payment must share a
 *       key or a {@code CreditRequested} can overtake the
 *       {@code DebitRequested} that preceded it.</li>
 * </ul>
 */
@Component
@Slf4j
public class KafkaSink implements MessageSink {

    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public void deliver(OutboxMessage message) {
        if (kafkaTemplate == null) {
            throw new IllegalStateException(
                    "KafkaSink selected but no KafkaTemplate is available");
        }
        String topic = topicFor(message.getEventType());
        try {
            kafkaTemplate
                    .send(topic, message.getAggregateId().toString(), message.getPayload())
                    .get(10, TimeUnit.SECONDS);   // wait: a silent drop is worse than a slow send
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing to " + topic, ie);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka publish failed for " + topic, e);
        }
    }

    /** {@code DebitRequested} becomes {@code payment.debit.requested}. */
    private String topicFor(String eventType) {
        String snake = eventType.replaceAll("([a-z])([A-Z])", "$1.$2").toLowerCase(Locale.ROOT);
        return "payment." + snake;
    }

    @Override
    public String name() {
        return "kafka";
    }
}
