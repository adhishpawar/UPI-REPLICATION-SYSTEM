package com.upi.payment.funds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.payment.domain.enums.FundingSource;
import com.upi.payment.domain.event.EventTypes;
import com.upi.payment.domain.event.FundsCommand;
import com.upi.payment.domain.event.FundsOutcome;
import com.upi.payment.messaging.IdempotentMessageHandler;
import com.upi.payment.observability.ExecutionRecorder;
import com.upi.payment.outbox.OutboxMessage;
import com.upi.payment.ports.EventPublisher;
import com.upi.payment.ports.FundsMovement;
import com.upi.payment.ports.FundsMover;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes funds-movement commands against the appropriate money-holder.
 *
 * <p>This is the component that was entirely missing. The saga published
 * {@code DebitRequested} and {@code CreditRequested}, and nothing anywhere
 * consumed them: no bank adapter existed and no broker was running. Every
 * payment therefore stopped at payee validation and stayed there. The platform
 * had a head and no body.
 *
 * <h3>Routing</h3>
 *
 * The mover is selected by the payment's {@link FundingSource}, so adding the
 * wallet later means adding one {@link FundsMover} bean -- no change to the
 * saga, the state machine, the handlers, or recovery. That is the return on
 * having drawn the port (D-003).
 *
 * <h3>Why the HTTP call happens inside a transaction</h3>
 *
 * {@link IdempotentMessageHandler} opens a transaction covering the duplicate
 * check, the work, and the record that the work was done. Here "the work"
 * includes a network call, so a database transaction is held open across it --
 * normally something to avoid.
 *
 * <p>It is the right trade here because the alternative is worse. If the
 * dedup record and the funds call were in separate transactions, a crash
 * between them would leave money moved and no record that this message had
 * been processed, so the next redelivery would move it again. Holding the
 * transaction across the call buys atomicity between "we did it" and "we
 * recorded that we did it", which is the property that makes at-least-once
 * delivery safe. The connection-pool cost is bounded by the client timeout,
 * which is why that timeout is short and mandatory.
 */
@Component
@Slf4j
public class FundsCommandHandler extends IdempotentMessageHandler {

    private static final String COMPONENT = "funds-command-handler";

    private final Map<FundingSource, FundsMover> movers;
    private final EventPublisher events;
    private final ObjectMapper objectMapper;
    private final ExecutionRecorder recorder;

    public FundsCommandHandler(List<FundsMover> moverBeans,
                               EventPublisher events,
                               ObjectMapper objectMapper,
                               ExecutionRecorder recorder) {
        this.movers = moverBeans.stream().collect(
                java.util.stream.Collectors.toMap(FundsMover::fundingSource, m -> m));
        this.events = events;
        this.objectMapper = objectMapper;
        this.recorder = recorder;
        log.info("FundsCommandHandler wired movers: {}", movers.keySet());
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EventTypes.DEBIT_REQUESTED,
                      EventTypes.CREDIT_REQUESTED,
                      EventTypes.REVERSAL_REQUESTED);
    }

    @Override
    public String consumerName() {
        return COMPONENT;
    }

    @Override
    protected void doHandle(OutboxMessage message) {
        FundsCommand command = read(message);

        recorder.record(command.traceId(), command.transactionId(), COMPONENT,
                "consume " + message.getEventType(), ExecutionRecorder.Kind.EVENT_CONS,
                ExecutionRecorder.Status.STARTED, null,
                "executing " + command.leg() + " of " + command.amount(), null);

        // Only BANK exists today. WALLET slots in here with no other change.
        FundsMover mover = movers.get(FundingSource.BANK);
        if (mover == null) {
            throw new IllegalStateException("No FundsMover available for BANK");
        }

        FundsMovement.Command cmd = new FundsMovement.Command(
                command.transactionId(), command.rrn(), command.leg(),
                command.accountNumber(), command.amount(), command.currency(),
                command.traceId(), simulateFor(command.leg(), command.simulate()));

        FundsMovement.Result result = switch (command.leg()) {
            case DEBIT -> mover.debit(cmd);
            case CREDIT -> mover.credit(cmd);
            case REVERSAL -> mover.reverse(cmd);
        };

        publishOutcome(command, result);
    }

    /**
     * Turn the mover's result into a fact on the outbox.
     *
     * <p>Published inside the same transaction as the dedup record, so the
     * outcome cannot be lost after the money has moved.
     */
    private void publishOutcome(FundsCommand command, FundsMovement.Result result) {
        String eventType = eventTypeFor(command.leg(), result.outcome());

        FundsOutcome outcome = new FundsOutcome(
                command.transactionId(), command.rrn(), command.leg(),
                result.outcome(), result.reference(), result.failureReason(),
                command.traceId());

        events.publish(EventTypes.AGGREGATE_TRANSACTION, command.transactionId(),
                eventType, outcome, command.traceId());

        recorder.record(command.traceId(), command.transactionId(), COMPONENT,
                "outbox.append " + eventType, ExecutionRecorder.Kind.EVENT_PUB,
                result.outcome() == FundsMovement.Outcome.SUCCEEDED
                        ? ExecutionRecorder.Status.OK : ExecutionRecorder.Status.FAILED,
                null,
                result.outcome() == FundsMovement.Outcome.UNKNOWN
                        ? "outcome UNKNOWN - the money may or may not have moved"
                        : String.valueOf(result.failureReason()),
                null);
    }

    /**
     * UNKNOWN maps to its own event type, never to {@code *Failed}.
     *
     * <p>If it mapped to failure, the saga would compensate a debit that might
     * have succeeded -- refunding a payer who was also paid, which creates
     * money. Keeping the third outcome distinct all the way through the event
     * vocabulary is what makes that mistake unavailable rather than merely
     * discouraged.
     */
    private String eventTypeFor(FundsMovement.Leg leg, FundsMovement.Outcome outcome) {
        if (outcome == FundsMovement.Outcome.UNKNOWN) {
            return EventTypes.FUNDS_OUTCOME_UNKNOWN;
        }
        boolean ok = outcome == FundsMovement.Outcome.SUCCEEDED;
        return switch (leg) {
            case DEBIT -> ok ? EventTypes.DEBIT_SUCCEEDED : EventTypes.DEBIT_FAILED;
            case CREDIT -> ok ? EventTypes.CREDIT_SUCCEEDED : EventTypes.CREDIT_FAILED;
            case REVERSAL -> ok ? EventTypes.REVERSAL_SUCCEEDED : EventTypes.REVERSAL_FAILED;
        };
    }

    /**
     * Decide whether an injected failure applies to this leg.
     *
     * <p>A scenario may target a specific leg -- {@code TIMEOUT_CREDIT} fails
     * only the credit, {@code TIMEOUT_DEBIT} only the debit -- because the two
     * teach different things. A debit that times out risks a payment that
     * silently never happens; a <em>credit</em> that times out is the dangerous
     * one, because the payer has already been debited and the naive reactions
     * (reverse, or retry) create or duplicate money.
     *
     * <p>An unsuffixed scenario applies to the first leg it reaches, which in
     * practice is the debit.
     */
    private String simulateFor(FundsMovement.Leg leg, String simulate) {
        if (simulate == null || simulate.isBlank()) {
            return null;
        }
        String upper = simulate.toUpperCase();
        if (upper.endsWith("_DEBIT")) {
            return leg == FundsMovement.Leg.DEBIT
                    ? upper.substring(0, upper.length() - "_DEBIT".length()) : null;
        }
        if (upper.endsWith("_CREDIT")) {
            return leg == FundsMovement.Leg.CREDIT
                    ? upper.substring(0, upper.length() - "_CREDIT".length()) : null;
        }
        // Never inject into a compensation: a reversal exists to put money
        // back, and deliberately breaking it would strand the payer.
        return leg == FundsMovement.Leg.REVERSAL ? null : upper;
    }

    private FundsCommand read(OutboxMessage message) {
        try {
            return objectMapper.readValue(message.getPayload(), FundsCommand.class);
        } catch (Exception e) {
            // Unreadable payload is not transient: redelivery will fail
            // identically until it dead-letters, which is the correct outcome.
            // It means a producer and a consumer disagree about the schema, and
            // that needs a human, not a retry.
            throw new IllegalStateException(
                    "Cannot deserialise FundsCommand from message " + message.getMessageId(), e);
        }
    }
}
