package com.upi.payment.messaging;

import com.upi.payment.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers outbox messages by calling handlers in this JVM.
 *
 * <p>This is not a shortcut or a mock. The durability guarantee, the ordering
 * guarantee, the retry behaviour, the dead-lettering and the at-least-once
 * semantics all come from the outbox table and the relay, none of which change
 * when a broker is introduced. What a broker adds is the ability to deliver to
 * a <em>different process</em> -- valuable, but a deployment property, not a
 * correctness one.
 *
 * <p>Consequence worth stating plainly: every idempotency, ordering and
 * duplicate-handling problem that Kafka would expose is already exposed here.
 * Code that is correct under this sink is correct under Kafka.
 */
@Component
@Slf4j
public class InProcessSink implements MessageSink {

    private final Map<String, List<MessageHandler>> handlersByType = new HashMap<>();

    public InProcessSink(List<MessageHandler> handlers) {
        for (MessageHandler h : handlers) {
            for (String type : h.eventTypes()) {
                handlersByType.computeIfAbsent(type, k -> new ArrayList<>()).add(h);
            }
        }
        log.info("InProcessSink wired {} handler(s) across {} event type(s): {}",
                handlers.size(), handlersByType.size(), handlersByType.keySet());
    }

    @Override
    public void deliver(OutboxMessage message) {
        List<MessageHandler> handlers = handlersByType.get(message.getEventType());

        if (handlers == null || handlers.isEmpty()) {
            // Not an error. Facts such as PaymentCompleted legitimately have no
            // consumer yet -- the notification service does not exist. What
            // would be an error is a *command* with no consumer, which is the
            // condition the platform was in before this work: the saga
            // published DebitRequested and nothing anywhere consumed it.
            log.debug("No handler for event type {} -- treating as delivered",
                    message.getEventType());
            return;
        }

        for (MessageHandler handler : handlers) {
            handler.handle(message);   // throws -> relay retries
        }
    }

    @Override
    public String name() {
        return "in-process";
    }
}
