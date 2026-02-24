package com.upi.vpa_service.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice   // Intercepts exceptions from ALL @RestController classes
@Slf4j
public class GlobalExceptionHandler {

    // Handles VPA not found → HTTP 404
    @ExceptionHandler(VpaNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleVpaNotFound(VpaNotFoundException ex) {
        log.warn("VPA not found: {}", ex.getMessage());
        return ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    //400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex)
    {
        //JAVA streams --> collect all field error messages into on String
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));

        return ErrorResponse.of("VALIDATION_FAILED", errors, HttpStatus.BAD_REQUEST);
    }

    //catch all for unexpected exceptions --> HTTP 500
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpected(Exception ex)
    {
        log.error("Unexpected error", ex);
        return ErrorResponse.of("INTERNAL_ERROR","An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR);
    }

}