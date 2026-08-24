package com.upi.bankservice.posting;

import com.upi.bankservice.dto.ApiResponse;
import com.upi.bankservice.models.Ledger;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The money-holder's API.
 *
 * <p>Three commands and one query. The query is the important one: it is what
 * lets a caller whose request timed out find out what actually happened,
 * rather than assume. Without it, the only way to resolve an unknown outcome
 * is to retry the command -- which risks posting twice -- or to give up, which
 * risks losing the payment.
 *
 * <h3>Status codes are a contract, not decoration</h3>
 *
 * <pre>
 *   200  the posting exists (whether created now or previously)
 *   422  a definite business refusal: insufficient funds, unknown account
 *   500  something went wrong and we cannot say whether the posting exists
 * </pre>
 *
 * <p>The caller maps 4xx to FAILED and everything else to UNKNOWN, so
 * returning 500 where 422 was meant sends a payment down the reconciliation
 * path unnecessarily -- and returning 422 where 500 was meant is far worse: it
 * tells the caller "definitely not posted" when the truth is "we do not know",
 * which is what licenses an incorrect reversal.
 */
@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Slf4j
public class PostingController {

    private final PostingService postingService;

    @PostMapping("/{accountNumber}/debit")
    public ResponseEntity<ApiResponse<PostingResult>> debit(
            @PathVariable String accountNumber,
            @Valid @RequestBody PostingRequest request) {
        return post(accountNumber, request, Ledger.TransactionType.DEBIT);
    }

    @PostMapping("/{accountNumber}/credit")
    public ResponseEntity<ApiResponse<PostingResult>> credit(
            @PathVariable String accountNumber,
            @Valid @RequestBody PostingRequest request) {
        return post(accountNumber, request, Ledger.TransactionType.CREDIT);
    }

    /**
     * Compensate a committed debit.
     *
     * <p>Not an undo. The original debit stays in the ledger and this adds an
     * inverse posting beside it, so the record shows what happened rather than
     * a tidied version of it.
     */
    @PostMapping("/{accountNumber}/reverse")
    public ResponseEntity<ApiResponse<PostingResult>> reverse(
            @PathVariable String accountNumber,
            @Valid @RequestBody PostingRequest request) {
        return post(accountNumber, request, Ledger.TransactionType.REVERSAL);
    }

    private ResponseEntity<ApiResponse<PostingResult>> post(
            String accountNumber, PostingRequest request, Ledger.TransactionType leg) {

        PostingResult result = postingService.post(
                request.getTxId(), leg, accountNumber,
                request.getAmount(), request.getRrn(), request.getSimulate());

        return ResponseEntity.ok(new ApiResponse<>(
                "SUCCESS",
                result.replayed() ? leg + " already posted (idempotent replay)"
                                  : leg + " posted",
                result));
    }

    /**
     * What did this bank record for the given transaction?
     *
     * <p>A read. No side effects, safe to call any number of times, and
     * therefore the only operation a caller may perform freely when it does
     * not know what happened.
     */
    @GetMapping("/postings/{txId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> posting(
            @PathVariable String txId,
            @RequestParam(required = false) String leg) {

        Map<String, Object> data = new HashMap<>();

        if (leg == null || leg.isBlank()) {
            List<Ledger> all = postingService.findAllPostings(txId);
            data.put("found", !all.isEmpty());
            data.put("postings", all.stream().map(PostingController::describe).toList());
            return ResponseEntity.ok(new ApiResponse<>("SUCCESS", "postings for " + txId, data));
        }

        Ledger.TransactionType type = Ledger.TransactionType.valueOf(leg.toUpperCase());
        Optional<Ledger> found = postingService.findPosting(txId, type);

        if (found.isEmpty()) {
            data.put("found", false);
            data.put("leg", type.name());
            data.put("status", "NOT_FOUND");
            return ResponseEntity.ok(new ApiResponse<>(
                    "SUCCESS", "no " + type + " posting for " + txId, data));
        }

        data.putAll(describe(found.get()));
        data.put("found", true);
        return ResponseEntity.ok(new ApiResponse<>("SUCCESS", "posting found", data));
    }

    /**
     * Ledger-derived balance, for reconciling against the stored balance.
     *
     * <p>The stored balance is materialised for fast reads; this is the value
     * the append-only ledger implies. If the two ever disagree, the ledger is
     * right -- it cannot be updated, and the balance can.
     */
    @GetMapping("/{accountNumber}/reconcile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconcile(
            @PathVariable String accountNumber) {

        BigDecimal derived = postingService.derivedBalance(accountNumber);
        Map<String, Object> data = new HashMap<>();
        data.put("accountNumber", accountNumber);
        data.put("ledgerDerivedBalance", derived);
        return ResponseEntity.ok(new ApiResponse<>("SUCCESS", "derived from ledger", data));
    }

    private static Map<String, Object> describe(Ledger l) {
        Map<String, Object> m = new HashMap<>();
        m.put("leg", l.getType().name());
        m.put("status", l.getStatus().name());
        m.put("reference", l.getReference());
        m.put("amount", l.getAmount());
        m.put("accountNumber", l.getAccountNumber());
        m.put("balanceAfter", l.getBalanceAfter());
        m.put("createdAt", String.valueOf(l.getCreatedAt()));
        return m;
    }

    // ── Error mapping ─────────────────────────────────────────────────────
    // See the class comment: getting these codes wrong is not cosmetic.

    @ExceptionHandler(PostingService.InsufficientFundsException.class)
    public ResponseEntity<ApiResponse<Void>> insufficientFunds(
            PostingService.InsufficientFundsException e) {
        // 422: a definite refusal. The caller may safely conclude FAILED.
        return ResponseEntity.unprocessableEntity()
                .body(new ApiResponse<>("FAILED", "INSUFFICIENT_FUNDS: " + e.getMessage(), null));
    }

    @ExceptionHandler(PostingService.AccountNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> accountNotFound(
            PostingService.AccountNotFoundException e) {
        return ResponseEntity.unprocessableEntity()
                .body(new ApiResponse<>("FAILED", "ACCOUNT_NOT_FOUND: " + e.getMessage(), null));
    }

    @ExceptionHandler(PostingService.DuplicatePostingException.class)
    public ResponseEntity<ApiResponse<Void>> duplicate(PostingService.DuplicatePostingException e) {
        // 409: a concurrent caller won. Not an error the caller should act on;
        // it means the posting exists. Re-query to obtain it.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>("DUPLICATE", e.getMessage(), null));
    }

    @ExceptionHandler(PostingService.PostingInProgressException.class)
    public ResponseEntity<ApiResponse<Void>> inProgress(PostingService.PostingInProgressException e) {
        // 409, deliberately not 4xx-as-failure. The caller must treat this as
        // "outcome still unknown", never as "did not happen".
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>("IN_PROGRESS", e.getMessage(), null));
    }

    @ExceptionHandler(FailureSimulator.SimulatedServerException.class)
    public ResponseEntity<ApiResponse<Void>> simulated(FailureSimulator.SimulatedServerException e) {
        // 500 on purpose: the caller must NOT conclude the posting did not
        // happen. Unknown is the honest answer here.
        log.warn("Returning simulated 500 - caller should treat outcome as UNKNOWN");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>("ERROR", e.getMessage(), null));
    }
}
