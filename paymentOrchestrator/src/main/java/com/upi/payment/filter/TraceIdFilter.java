package com.upi.payment.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes the correlation ID for a request.
 *
 * <p><b>Replaces {@code IdempotencyFilter}, which was removed.</b> That filter
 * did two things and got both wrong:
 *
 * <ol>
 *   <li>It read the header {@code "Idempotency=Key"} -- an equals sign instead
 *       of a hyphen. The lookup always returned null, so every payment request
 *       was rejected as missing its idempotency key. The endpoint was
 *       unreachable.</li>
 *   <li>Its duplicate check was {@code findByIdempotencyKey(...)} followed by
 *       letting the request proceed: a check-then-act race. Two concurrent
 *       requests carrying the same key both read "absent" and both continue.
 *       A filter cannot close that window, because the window is between the
 *       read and the insert.</li>
 * </ol>
 *
 * <p>Idempotency now lives entirely in {@code PaymentServiceImpl}, where the
 * insert and the duplicate handling are one atomic operation guarded by the
 * UNIQUE constraint on {@code transactions.idempotency_key}. The constraint is
 * the guard; a preceding read can only ever be an optimisation.
 *
 * <p>What remains here is correlation, which genuinely is a cross-cutting
 * concern: one identifier that ties the HTTP request, every log line, every
 * outbox message, every execution event, and every recovery action for a
 * single payment together. Without it, diagnosing a distributed failure means
 * reading timestamps and guessing.
 */
@Component
@Order(1)
@Slf4j
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        // Honour an inbound trace id so a caller (or the showcase) can follow
        // a request it initiated; otherwise mint one.
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // MDC is thread-local and threads are pooled. Not clearing it
            // leaks this request's trace id onto whatever request the thread
            // serves next -- which produces logs that are confidently wrong.
            MDC.remove(MDC_KEY);
        }
    }
}
