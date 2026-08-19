package com.upi.payment.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.payment.domain.event.EventTypes;
import com.upi.payment.domain.event.FundsOutcome;
import com.upi.payment.messaging.IdempotentMessageHandler;
import com.upi.payment.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Feeds funds-movement outcomes back into the saga.
 *
 * <p>The reply half of the command/reply pair: {@code FundsCommandHandler}
 * carries out a movement and publishes what happened; this consumes that and
 * advances the payment.
 *
 * <p>Idempotent by inheritance, which matters here more than anywhere else in
 * the platform. A redelivered {@code DebitSucceeded} that reached the saga
 * twice would attempt {@code DEBITED -> DEBITED}, which the state machine
 * rejects -- so the message would fail forever and eventually dead-letter, and
 * a perfectly healthy payment would look broken. The deduplication in
 * {@link IdempotentMessageHandler} drops the second copy before it gets there.
 */
@Component
@Slf4j
public class FundsOutcomeHandler extends IdempotentMessageHandler {

    private final SagaOrchestrator saga;
    private final ObjectMapper objectMapper;

    public FundsOutcomeHandler(SagaOrchestrator saga, ObjectMapper objectMapper) {
        this.saga = saga;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(
                EventTypes.DEBIT_SUCCEEDED, EventTypes.DEBIT_FAILED,
                EventTypes.CREDIT_SUCCEEDED, EventTypes.CREDIT_FAILED,
                EventTypes.REVERSAL_SUCCEEDED, EventTypes.REVERSAL_FAILED,
                EventTypes.FUNDS_OUTCOME_UNKNOWN);
    }

    @Override
    public String consumerName() {
        return "saga-outcome-handler";
    }

    @Override
    protected void doHandle(OutboxMessage message) {
        FundsOutcome outcome = read(message);

        // Routing is by leg, not by event type, because FundsOutcomeUnknown
        // covers all three legs. The leg is what determines which part of the
        // saga is waiting for an answer.
        switch (outcome.leg()) {
            case DEBIT -> saga.onDebitOutcome(outcome);
            case CREDIT -> saga.onCreditOutcome(outcome);
            case REVERSAL -> saga.onReversalOutcome(outcome);
        }
    }

    private FundsOutcome read(OutboxMessage message) {
        try {
            return objectMapper.readValue(message.getPayload(), FundsOutcome.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot deserialise FundsOutcome from message " + message.getMessageId(), e);
        }
    }
}
