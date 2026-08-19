package com.upi.payment.vpa;

import com.upi.payment.exception.VpaNotFoundException;
import com.upi.payment.exception.VpaServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * Talks to {@code vpa-service}.
 *
 * <h3>Why this call is retried when funds movements are not</h3>
 *
 * {@code @Retry} is correct here and would be dangerous on a debit. VPA
 * resolution is a <b>read</b>: it has no side effect, so issuing it three
 * times leaves the world exactly as one call would. A debit is a write, and a
 * write whose outcome is unknown must never be repeated blindly -- the first
 * attempt may have succeeded and the response merely been lost.
 *
 * <p>That asymmetry, safe-to-retry reads versus unsafe-to-retry writes, is the
 * single most useful heuristic for deciding retry policy in a payment system.
 *
 * <h3>Circuit breaker</h3>
 *
 * Opens after a sustained failure rate and then fails fast for a cooling
 * period. Its purpose is not to protect this service, it is to stop this
 * service hammering a struggling dependency -- retry storms are how a degraded
 * service becomes a dead one.
 *
 * <p>Both failure modes here are safe, because a VPA lookup happens
 * <em>before</em> any money moves. Failing at this point costs the user a
 * retry and nothing else.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VpaServiceClient {

    private final WebClient webClient;

    @Value("${services.vpa.base-url:http://localhost:8081}")
    private String vpaServiceBaseUrl;

    /**
     * Public resolution: name only.
     * Used to show the payer who they are about to pay.
     */
    @CircuitBreaker(name = "vpa-service", fallbackMethod = "resolutionFallback")
    @Retry(name = "vpa-service")
    public VpaResolutionResponse resolveVpa(String vpaAddress) {
        try {
            return webClient.get()
                    .uri(vpaServiceBaseUrl + "/api/v1/vpa/{address}", vpaAddress)
                    .retrieve()
                    .bodyToMono(VpaResolutionResponse.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
        } catch (WebClientResponseException.NotFound ex) {
            // A 404 is an answer, not a failure. It must not count towards the
            // circuit breaker and must not be retried: the VPA will still not
            // exist on the second attempt.
            throw new VpaNotFoundException(vpaAddress);
        } catch (Exception ex) {
            log.error("VPA resolution failed for {}: {}", vpaAddress, ex.toString());
            throw new VpaServiceUnavailableException("VPA service unavailable");
        }
    }

    /**
     * Internal resolution: account number and IFSC.
     *
     * <p>Needed because a payment cannot be executed against a name. Kept on a
     * separate endpoint so that account numbers are not returned to payer-facing
     * callers.
     */
    @CircuitBreaker(name = "vpa-service", fallbackMethod = "accountFallback")
    @Retry(name = "vpa-service")
    public VpaAccountResponse resolveAccount(String vpaAddress) {
        try {
            return webClient.get()
                    .uri(vpaServiceBaseUrl + "/api/v1/vpa/{address}/account", vpaAddress)
                    .retrieve()
                    .bodyToMono(VpaAccountResponse.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
        } catch (WebClientResponseException.NotFound ex) {
            throw new VpaNotFoundException(vpaAddress);
        } catch (Exception ex) {
            log.error("VPA account resolution failed for {}: {}", vpaAddress, ex.toString());
            throw new VpaServiceUnavailableException("VPA service unavailable");
        }
    }

    /**
     * Called when the circuit is open.
     *
     * <p>Throws rather than returning a placeholder. A fallback that invented a
     * payee would let a payment proceed against a name nobody verified -- the
     * kind of "graceful degradation" that is only graceful until it moves money
     * to the wrong account. Failing loudly before any funds move is the correct
     * degradation here.
     */
    @SuppressWarnings("unused")
    public VpaResolutionResponse resolutionFallback(String vpaAddress, Throwable ex) {
        log.warn("VPA circuit OPEN, failing fast for {}: {}", vpaAddress, ex.toString());
        if (ex instanceof VpaNotFoundException notFound) {
            throw notFound;
        }
        throw new VpaServiceUnavailableException(
                "VPA service is unavailable. Please retry shortly.");
    }

    @SuppressWarnings("unused")
    public VpaAccountResponse accountFallback(String vpaAddress, Throwable ex) {
        log.warn("VPA circuit OPEN, failing fast for {}: {}", vpaAddress, ex.toString());
        if (ex instanceof VpaNotFoundException notFound) {
            throw notFound;
        }
        throw new VpaServiceUnavailableException(
                "VPA service is unavailable. Please retry shortly.");
    }
}
