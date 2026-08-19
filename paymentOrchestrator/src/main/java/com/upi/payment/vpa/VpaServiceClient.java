package com.upi.payment.vpa;


import com.upi.payment.domain.dto.VpaResolutionResponse;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class VpaServiceClient
{
    private final WebClient webClient;

    @Value("${services.vpa.base-url:http://localhost:8081}")
    private String vpaServiceBaseUrl;

    /**
     * Resolve a VPA address to account holder details.
     * @CircuitBreaker: trips after 5 failures in 10 seconds.
     *   Open state duration: 30 seconds.
     *   Calls the fallback method when circuit is OPEN.
     * @Retry: retries up to 3 times with exponential backoff (1s, 2s, 4s).
     */
    @CircuitBreaker(name = "vpa-service", fallbackMethod = "vpaResolutionFallback")
    @Retry(name = "vpa-service")
    public VpaResolutionResponse resolveVpa(String vpaAddress) {
        try{
            return webClient.get()
                    .uri(vpaServiceBaseUrl + "/api/v1/vpa/{address}", vpaAddress)
                    .retrieve()
                    .onStatus(status -> status.value() == 404,
                            res -> res.createException().map(ex ->
                                    new VpaNotFoundException(vpaAddress)))
                    .bodyToMono(VpaResolutionResponse.class)
                    .timeout(java.time.Duration.ofSeconds(3)) //3s max wait
                    .block();  // Synchronous call — acceptable since this is on-demand
        }catch (WebClientResponseException.NotFound ex) {
            throw new VpaNotFoundException(vpaAddress);
        }catch (Exception ex) {
            log.error("VPA Service call failed for {} : {}", vpaAddress, ex.getMessage());
            throw new VpaServiceUnavailableException("Vpa Service unavailable");
        }
    }

    /**
     * Fallback: called when circuit breaker is OPEN.
     * Returns a safe default that triggers payment failure with clear reason.
     */
    public VpaResolutionResponse vpaResolutionFallback(
            String vpaAddress, Exception ex) {
        log.warn("VPA CircuitBreaker OPEN — fallback for {}: {}",
                vpaAddress, ex.getMessage());
        throw new VpaServiceUnavailableException(
                "VPA Service is currently unavailable. Please retry in 30 seconds.");
    }

}
