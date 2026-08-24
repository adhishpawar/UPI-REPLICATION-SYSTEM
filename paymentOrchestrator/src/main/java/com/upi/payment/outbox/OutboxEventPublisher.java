package com.upi.payment.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.payment.ports.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes a domain event into the outbox, inside the caller's transaction.
 *
 * <p>{@code Propagation.MANDATORY} is the important annotation here. It makes
 * the JVM enforce the invariant that a comment could only ask for: this method
 * throws if invoked outside a transaction. There is therefore no way to
 * accidentally publish an event that is not bound to a committed state change
 * -- the mistake the previous {@code KafkaTemplate.send()} call made on every
 * saga step.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher implements EventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, UUID aggregateId, String eventType,
                        Object payload, String traceId) {
        try {
            OutboxMessage message = OutboxMessage.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .traceId(traceId != null ? traceId : MDC.get("traceId"))
                    .build();

            outboxRepository.save(message);
            log.debug("Outbox appended: type={} aggregate={}", eventType, aggregateId);

        } catch (JsonProcessingException e) {
            // Deliberately fatal. If the event cannot be serialised, the state
            // change that produced it must not commit either -- a committed
            // state with no corresponding event is exactly the inconsistency
            // the outbox exists to prevent.
            throw new IllegalStateException(
                    "Cannot serialise outbox payload for " + eventType, e);
        }
    }
}
