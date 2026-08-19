package com.upi.payment.funds;

import com.upi.payment.domain.enums.FundingSource;
import com.upi.payment.observability.ExecutionRecorder;
import com.upi.payment.ports.FundsMovement;
import com.upi.payment.ports.FundsMover;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Moves money by calling {@code bank-service} over HTTP.
 *
 * <p>{@code bank-service} is the money-holder: it owns the accounts, the
 * balances and the ledger. This class owns none of that; it only knows how to
 * ask.
 *
 * <h3>The one thing this class must get right</h3>
 *
 * Mapping failures to the correct {@link FundsMovement.Outcome}:
 *
 * <pre>
 *   HTTP 200          -> SUCCEEDED   the bank confirms the posting
 *   HTTP 4xx business -> FAILED      the bank says no, definitively
 *   timeout           -> UNKNOWN     no answer; the posting may exist
 *   connection error  -> UNKNOWN     ditto
 *   HTTP 5xx          -> UNKNOWN     the request may have been processed
 *                                    before the server failed to reply
 * </pre>
 *
 * <p>The tempting simplification is to fold everything that is not a 200 into
 * "failed". That single line of code is how a payment system creates money:
 * the orchestrator sees FAILED, compensates by reversing the debit, and if the
 * original request had in fact been processed, the payee keeps the credit
 * while the payer gets refunded.
 *
 * <p>Note also what is <em>absent</em>: any retry annotation. There is a
 * {@code @Retry} on VPA resolution because that is a read with no side effect.
 * There is deliberately none here. Retrying a funds movement whose outcome is
 * unknown is the same mistake in a different disguise -- it risks a second
 * posting rather than a phantom reversal. Unknown outcomes go to
 * {@code UNCERTAIN} and are reconciled.
 */
@Component
@Slf4j
public class BankFundsMover implements FundsMover {

    private static final String COMPONENT = "bank-service";

    private final WebClient webClient;
    private final ExecutionRecorder recorder;
    private final String baseUrl;
    private final Duration timeout;

    public BankFundsMover(WebClient webClient,
                          ExecutionRecorder recorder,
                          @Value("${services.bank.base-url:http://localhost:8084}") String baseUrl,
                          @Value("${services.bank.timeout-seconds:5}") int timeoutSeconds) {
        this.webClient = webClient;
        this.recorder = recorder;
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public FundingSource fundingSource() {
        return FundingSource.BANK;
    }

    @Override
    public FundsMovement.Result debit(FundsMovement.Command command) {
        return post(command, "debit");
    }

    @Override
    public FundsMovement.Result credit(FundsMovement.Command command) {
        return post(command, "credit");
    }

    @Override
    public FundsMovement.Result reverse(FundsMovement.Command command) {
        return post(command, "reverse");
    }

    @SuppressWarnings("unchecked")
    private FundsMovement.Result post(FundsMovement.Command cmd, String operation) {
        String uri = baseUrl + "/accounts/" + cmd.accountNumber() + "/" + operation;
        long start = System.nanoTime();

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("txId", cmd.transactionId().toString());
        body.put("leg", cmd.leg().name());
        body.put("amount", cmd.amount());
        body.put("rrn", cmd.rrn());
        if (cmd.simulate() != null) {
            body.put("simulate", cmd.simulate());
        }

        try {
            Map<String, Object> response = webClient.post()
                    .uri(uri)
                    .header("X-Trace-Id", cmd.traceId() == null ? "" : cmd.traceId())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(timeout)
                    .block();

            int ms = msSince(start);
            Map<String, Object> data = response == null
                    ? null : (Map<String, Object>) response.get("data");

            if (data == null) {
                recorder.record(cmd.traceId(), cmd.transactionId(), COMPONENT,
                        "POST /accounts/{acct}/" + operation,
                        ExecutionRecorder.Kind.HTTP_OUT, ExecutionRecorder.Status.FAILED,
                        ms, "empty response body", null);
                return FundsMovement.Result.unknown("Empty response from bank-service");
            }

            String reference = str(data.get("reference"));
            BigDecimal balanceAfter = data.get("balanceAfter") == null
                    ? null : new BigDecimal(String.valueOf(data.get("balanceAfter")));

            recorder.record(cmd.traceId(), cmd.transactionId(), COMPONENT,
                    "POST /accounts/{acct}/" + operation,
                    ExecutionRecorder.Kind.HTTP_OUT, ExecutionRecorder.Status.OK,
                    ms, "200 OK ref=" + reference,
                    Map.of("leg", cmd.leg().name(), "amount", cmd.amount(),
                           "reference", reference == null ? "" : reference));

            return FundsMovement.Result.succeeded(reference, balanceAfter);

        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            int ms = msSince(start);

            // 409 means "already in flight" or "a concurrent caller won" --
            // in both cases the posting may well exist. It is a 4xx, but it is
            // emphatically NOT a definite refusal, and mapping it to FAILED
            // would license a reversal against money that did move.
            if (e.getStatusCode().value() == 409) {
                recorder.record(cmd.traceId(), cmd.transactionId(), COMPONENT,
                        "POST /accounts/{acct}/" + operation,
                        ExecutionRecorder.Kind.HTTP_OUT, ExecutionRecorder.Status.FAILED,
                        ms, "409 - posting already in flight, outcome UNKNOWN", null);
                return FundsMovement.Result.unknown("POSTING_IN_FLIGHT");
            }

            if (e.getStatusCode().is4xxClientError()) {
                // A definite "no" from the money-holder. The only outcome we
                // are entitled to call FAILED.
                String reason = extractReason(e.getResponseBodyAsString(), e.getStatusCode().value());
                recorder.record(cmd.traceId(), cmd.transactionId(), COMPONENT,
                        "POST /accounts/{acct}/" + operation,
                        ExecutionRecorder.Kind.HTTP_OUT, ExecutionRecorder.Status.FAILED,
                        ms, e.getStatusCode() + " " + reason, null);
                return FundsMovement.Result.failed(reason);
            }

            // 5xx: the bank may have committed the posting and then failed to
            // reply. We are not entitled to assume anything.
            recorder.record(cmd.traceId(), cmd.transactionId(), COMPONENT,
                    "POST /accounts/{acct}/" + operation,
                    ExecutionRecorder.Kind.HTTP_OUT, ExecutionRecorder.Status.FAILED,
                    ms, e.getStatusCode() + " - outcome unknown", null);
            return FundsMovement.Result.unknown("bank-service returned " + e.getStatusCode());

        } catch (Exception e) {
            int ms = msSince(start);
            boolean timedOut = e instanceof TimeoutException
                    || e.getCause() instanceof TimeoutException
                    || e.getClass().getSimpleName().contains("Timeout");

            log.warn("Funds movement outcome UNKNOWN txn={} leg={} op={}: {}",
                    cmd.transactionId(), cmd.leg(), operation, e.toString());

            recorder.record(cmd.traceId(), cmd.transactionId(), COMPONENT,
                    "POST /accounts/{acct}/" + operation,
                    ExecutionRecorder.Kind.HTTP_OUT,
                    timedOut ? ExecutionRecorder.Status.TIMEOUT : ExecutionRecorder.Status.FAILED,
                    ms,
                    timedOut
                        ? "no response within " + timeout.toSeconds() + "s - outcome UNKNOWN"
                        : "transport error - outcome UNKNOWN: " + e.getClass().getSimpleName(),
                    null);

            return FundsMovement.Result.unknown(
                    timedOut ? "TIMEOUT_NO_RESPONSE" : "TRANSPORT_ERROR");
        }
    }

    /**
     * Ask the bank what it actually recorded. A read: no side effect, safe to
     * repeat, and the only honest way to resolve an unknown outcome.
     */
    @Override
    @SuppressWarnings("unchecked")
    public FundsMovement.LedgerRecord query(UUID transactionId, FundsMovement.Leg leg,
                                            String accountNumber) {
        String uri = baseUrl + "/accounts/postings/" + transactionId + "?leg=" + leg.name();
        long start = System.nanoTime();

        try {
            Map<String, Object> response = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(timeout)
                    .block();

            Map<String, Object> data = response == null
                    ? null : (Map<String, Object>) response.get("data");

            if (data == null || Boolean.FALSE.equals(data.get("found"))) {
                recorder.record(null, transactionId, COMPONENT,
                        "GET /accounts/postings/{txId}",
                        ExecutionRecorder.Kind.HTTP_OUT, ExecutionRecorder.Status.OK,
                        msSince(start), "bank has NO " + leg + " posting for this transaction", null);
                return FundsMovement.LedgerRecord.notFound(leg);
            }

            String status = str(data.get("status"));
            String reference = str(data.get("reference"));
            BigDecimal amount = data.get("amount") == null
                    ? null : new BigDecimal(String.valueOf(data.get("amount")));

            recorder.record(null, transactionId, COMPONENT,
                    "GET /accounts/postings/{txId}",
                    ExecutionRecorder.Kind.HTTP_OUT, ExecutionRecorder.Status.OK,
                    msSince(start),
                    "bank HAS a " + leg + " posting: " + status + " ref=" + reference, null);

            return new FundsMovement.LedgerRecord(true, leg, status, reference, amount);

        } catch (Exception e) {
            recorder.record(null, transactionId, COMPONENT,
                    "GET /accounts/postings/{txId}",
                    ExecutionRecorder.Kind.HTTP_OUT, ExecutionRecorder.Status.FAILED,
                    msSince(start), "cannot reach bank to reconcile: " + e.getClass().getSimpleName(), null);
            throw new FundsMovement.MoverUnavailableException(
                    "Cannot reach bank-service to reconcile " + transactionId, e);
        }
    }

    private String extractReason(String body, int status) {
        if (body == null || body.isBlank()) return "HTTP_" + status;
        String lower = body.toLowerCase();
        if (lower.contains("insufficient")) return "INSUFFICIENT_FUNDS";
        if (lower.contains("not found")) return "ACCOUNT_NOT_FOUND";
        if (lower.contains("blocked") || lower.contains("frozen")) return "ACCOUNT_BLOCKED";
        return "HTTP_" + status;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private int msSince(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
