package com.github.guoyyit.fibroutingjava.web;

import com.github.guoyyit.fibroutingjava.fib.LookupCancelledException;
import com.github.guoyyit.fibroutingjava.fib.LookupTimeoutException;
import com.github.guoyyit.fibroutingjava.fib.RouteRejectedException;
import com.github.guoyyit.fibroutingjava.persistence.FibPersistenceService;
import com.github.guoyyit.fibroutingjava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将领域异常映射为带可区分原因码的 HTTP 错误响应。 */
@RestControllerAdvice
public class FibExceptionHandler {

    @ExceptionHandler(RouteRejectedException.class)
    public ResponseEntity<ErrorResponse> rejected(RouteRejectedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ex.reason().name(), ex.getMessage()));
    }

    @ExceptionHandler(LookupTimeoutException.class)
    public ResponseEntity<ErrorResponse> timeout(LookupTimeoutException ex) {
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                .body(new ErrorResponse("LOOKUP_TIMEOUT", ex.getMessage()));
    }

    @ExceptionHandler(LookupCancelledException.class)
    public ResponseEntity<ErrorResponse> cancelled(LookupCancelledException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("LOOKUP_CANCELLED", ex.getMessage()));
    }

    @ExceptionHandler(FibPersistenceService.CorruptFibFileException.class)
    public ResponseEntity<ErrorResponse> corrupt(FibPersistenceService.CorruptFibFileException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("PERSISTENCE_CORRUPT", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", ex.getMessage()));
    }

}

