package com.upi.vpa_service.exception;

import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

@Getter
@Builder
public class ErrorResponse {
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

