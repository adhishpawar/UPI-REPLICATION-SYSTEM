package com.upi.payment.exception;

/**
 * ROLE: Intercepts all unhandled exceptions from @RestController classes and
 *       converts them to a consistent JSON error response.
 *
 * LOGIC: @RestControllerAdvice = applied to all controllers in the same package tree.
 *        @ExceptionHandler(X.class) = invoked when X is thrown in any controller call.
 *        Spring picks the MOST SPECIFIC matching handler.
 *
 * STANDARD ERROR SHAPE (every error looks the same):
 *   {
 *     "errorCode":  "TRANSACTION_NOT_FOUND",
 *     "message":    "Transaction not found: 7f3a8b12-...",
 *     "httpStatus": 404,
 *     "timestamp":  "2024-03-27T10:15:30"
 *   }
 *
 * FINANCIAL LOGGING NOTE:
 *   - WARN level for business errors (not found, invalid state)
 *   - ERROR level for unexpected system errors only
 *   - NEVER log the full payment amount + payer + payee together in one log line
 *     (PII + financial data combination is a compliance risk)
 */

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @Getter
    @Builder
    public static class ErrorResponse {
        private String errorCode;
        private String message;
        private int    httpStatus;
        private String timestamp;

        public static ErrorResponse of(String code, String msg, HttpStatus status) {
            return ErrorResponse.builder()
                    .errorCode(code).message(msg)
                    .httpStatus(status.value())
                    .timestamp(LocalDateTime.now().toString())
                    .build();
        }
    }

    // 404 — Transaction not found (or not owned by this user)
    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(TransactionNotFoundException ex) {
        log.warn("Transaction not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.NOT_FOUND));
    }

    // 409 — Duplicate payment (idempotency key already processed)
    /**
     * 403: the caller is authenticated, but this is not their VPA.
     * See {@link VpaOwnershipException} for why this is not a 404.
     */
    @ExceptionHandler(VpaOwnershipException.class)
    public ResponseEntity<ErrorResponse> handleVpaOwnership(VpaOwnershipException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.FORBIDDEN));
    }

    @ExceptionHandler(DuplicatePaymentException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicatePaymentException ex) {
        log.warn("Duplicate payment attempt: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.CONFLICT));
    }

    // 409 — Invalid state transition (should not reach client normally)
    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(InvalidStateTransitionException ex) {
        log.error("Invalid state transition — possible bug: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.CONFLICT));
    }

    // 400 — Self payment or invalid amount
    @ExceptionHandler({SelfPaymentException.class, PaymentAmountException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(PaymentBaseException ex) {
        log.warn("Bad payment request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.BAD_REQUEST));
    }

    // 400 — @Valid annotation failures on DTOs
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", errors, HttpStatus.BAD_REQUEST));
    }

    // 400 — Missing required headers (Idempotency-Key, X-User-Id)
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        String msg = "Required header missing: " + ex.getHeaderName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("MISSING_HEADER", msg, HttpStatus.BAD_REQUEST));
    }

    // 500 — Catch-all for unexpected errors
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error in Payment Orchestrator", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR",
                        "An unexpected error occurred. Our team has been notified.",
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }
}