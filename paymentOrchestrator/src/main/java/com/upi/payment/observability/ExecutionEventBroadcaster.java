package com.upi.payment.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans execution events out to connected Server-Sent Events subscribers.
 *
 * <h3>Why SSE rather than WebSocket</h3>
 *
 * The traffic is entirely one-directional: the backend describes what it is
 * doing and the viewer watches. SSE is plain HTTP, needs no handshake, no
 * sub-protocol and no client library, and reconnects on its own. A WebSocket
 * would add a bidirectional channel that nothing would ever send on. Choosing
 * the smaller mechanism that fits is not a compromise -- it is the design.
 *
 * <h3>Why this is not the source of truth</h3>
 *
 * Events are persisted to {@code execution_events} <em>first</em>, then
 * broadcast. A subscriber who connects late, disconnects, or is not there at
 * all loses nothing: the full trace is queryable by transaction id afterwards.
 * The stream is a convenience over durable data, never the data itself.
 *
 * <p>Consequently the showcase can be closed, reopened, or deleted outright
 * without affecting the backend, and a payment behaves identically whether
 * anyone is watching or not.
 */
@Component
@Slf4j
public class ExecutionEventBroadcaster {

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Register a subscriber.
     *
     * @param timeoutMs how long the connection may stay open; the browser's
     *                  {@code EventSource} reconnects automatically afterwards
     */
    public SseEmitter subscribe(long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        subscribers.add(emitter);

        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"subscribers\":" + subscribers.size() + "}"));
        } catch (IOException e) {
            subscribers.remove(emitter);
        }

        log.debug("SSE subscriber added, now {}", subscribers.size());
        return emitter;
    }

    /**
     * Push one event to everyone listening.
     *
     * <p>A dead subscriber is dropped rather than retried. Broadcasting is
     * best-effort by design: a browser that closed its tab must not be able to
     * slow down, or fail, payment processing.
     */
    public void publish(ExecutionEvent event) {
        if (subscribers.isEmpty()) {
            return;
        }
        String json = toJson(event);
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("execution").data(json));
            } catch (Exception e) {
                subscribers.remove(emitter);
            }
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Hand-built JSON rather than Jackson.
     *
     * <p>Deliberate: this runs on the payment's own thread, for every step of
     * every payment. It is a fixed, flat shape with no polymorphism, so a
     * string builder is both faster and completely predictable. Jackson would
     * be the right call the moment the shape stops being fixed.
     */
    private String toJson(ExecutionEvent e) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
          .append("\"id\":").append(e.getId()).append(',')
          .append("\"traceId\":\"").append(esc(e.getTraceId())).append("\",")
          .append("\"transactionId\":").append(e.getTransactionId() == null
                ? "null" : "\"" + e.getTransactionId() + "\"").append(',')
          .append("\"seq\":").append(e.getSeq()).append(',')
          .append("\"component\":\"").append(esc(e.getComponent())).append("\",")
          .append("\"operation\":\"").append(esc(e.getOperation())).append("\",")
          .append("\"kind\":\"").append(e.getKind()).append("\",")
          .append("\"status\":\"").append(e.getStatus()).append("\",")
          .append("\"latencyMs\":").append(e.getLatencyMs()).append(',')
          .append("\"message\":").append(e.getMessage() == null
                ? "null" : "\"" + esc(e.getMessage()) + "\"").append(',')
          .append("\"detail\":").append(e.getDetail() == null ? "null" : e.getDetail()).append(',')
          .append("\"at\":\"").append(e.getAt()).append('"')
          .append('}');
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
