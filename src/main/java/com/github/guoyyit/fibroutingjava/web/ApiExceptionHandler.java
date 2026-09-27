package com.github.guoyyit.fibroutingjava.web;

import com.github.guoyyit.fibroutingjava.model.RouteValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps domain errors to distinguishable HTTP responses carrying the error code. */
@RestControllerAdvice
public final class ApiExceptionHandler {

    @ExceptionHandler(RouteValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(RouteValidationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("errorCode", ex.errorCode().name(), "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("errorCode", "MALFORMED_PREFIX", "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("errorCode", "ILLEGAL_STATE", "message", String.valueOf(ex.getMessage())));
    }
}
