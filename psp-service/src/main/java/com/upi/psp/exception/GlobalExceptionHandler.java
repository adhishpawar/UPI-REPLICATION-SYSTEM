package com.upi.psp.exception;

// ─────────────────────────────────────────────────────────────────────────────
// FILE: GlobalExceptionHandler.java  +  ErrorResponse (inner static class)
//
// ROLE: Intercepts ALL unhandled exceptions from any @RestController in the
//       service and converts them to a consistent JSON error response.
//
// LOGIC:
//   Without this class, Spring returns ugly default error HTML or partial JSON.
//   With this class, every error has the same shape:
//     {
//       "errorCode":  "INVALID_CREDENTIALS",
//       "message":    "Invalid credentials. Please check your MPIN.",
//       "httpStatus": 401,
//       "timestamp":  "2025-02-27T10:15:30"
//     }
//   This consistent shape means the mobile app can handle all errors uniformly.
//
// JAVA CONCEPT: @RestControllerAdvice = @ControllerAdvice + @ResponseBody.
//   @ControllerAdvice: applied to all @RestController classes in the same package.
//   @ExceptionHandler(X.class): invoked when exception X is thrown anywhere in
//   a controller call stack.
//
//   Spring picks the MOST SPECIFIC handler. If both InvalidCredentialsException
//   and AuthBaseException handlers exist, Spring picks InvalidCredentialsException
//   for InvalidCredentialsException throws (more specific wins).
// ─────────────────────────────────────────────────────────────────────────────

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── Standard error response shape — all errors look the same ─────────────
    @Getter
    @Builder
    public static class ErrorResponse {
        private String errorCode;
        private String message;
        private int    httpStatus;
        private String timestamp;

        public static ErrorResponse of(String code, String msg, HttpStatus status) {
            return ErrorResponse.builder()
                    .errorCode(code)
                    .message(msg)
                    .httpStatus(status.value())
                    .timestamp(LocalDateTime.now().toString())
                    .build();
        }
    }

    // ── 404 Not Found ─────────────────────────────────────────────────────────
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        log.warn("User not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.NOT_FOUND));
    }

    // ── 409 Conflict ──────────────────────────────────────────────────────────
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(UserAlreadyExistsException ex) {
        log.warn("Duplicate registration attempt: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.CONFLICT));
    }

    // ── 401 Unauthorized — invalid credentials (login failure) ───────────────
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        // NOTE: Log at WARN not ERROR — failed logins are expected, not system errors
        log.warn("Invalid credentials attempt");  // No details logged — avoid leaking info
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.UNAUTHORIZED));
    }

    // ── 401 Unauthorized — expired JWT ───────────────────────────────────────
    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<ErrorResponse> handleTokenExpired(TokenExpiredException ex) {
        log.debug("JWT expired: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.UNAUTHORIZED));
    }

    // ── 401 Unauthorized — invalid JWT ───────────────────────────────────────
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex) {
        log.warn("Invalid token: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.UNAUTHORIZED));
    }

    // ── 403 Forbidden — account locked or MPIN not set ───────────────────────
    @ExceptionHandler({AccountLockedException.class, MpinNotSetException.class})
    public ResponseEntity<ErrorResponse> handleForbidden(AuthBaseException ex) {
        log.warn("Access forbidden: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.FORBIDDEN));
    }

    // ── 400 Bad Request — MPIN already set, invalid mobile ───────────────────
    @ExceptionHandler({MpinAlreadySetException.class, InvalidMobileNumberException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(AuthBaseException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.BAD_REQUEST));
    }

    // ── 429 Too Many Requests — rate limited ─────────────────────────────────
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(TooManyLoginAttemptsException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(),
                        HttpStatus.TOO_MANY_REQUESTS));
    }

    // ── 400 Bad Request — @Valid annotation failures ──────────────────────────
    // Triggered when @NotBlank, @Pattern, @Size validations fail on DTOs
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // Java Streams: collect all field error messages into comma-separated string
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", errors, HttpStatus.BAD_REQUEST));
    }

    // ── 500 Internal Server Error — catch-all ────────────────────────────────
    // Catches anything not handled by the above. Logs the full stack trace.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error in PSP Service", ex);  // Full stack trace logged here
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR",
                        "An unexpected error occurred. Please try again later.",
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
