package com.portfolio.cairn.web;

import com.portfolio.cairn.exception.EvictionFailedException;
import com.portfolio.cairn.exception.InvalidTtlException;
import com.portfolio.cairn.exception.KeyNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(KeyNotFoundException.class)
    public ResponseEntity<CacheDtos.ErrorResponse> handleKeyNotFound(KeyNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(CacheDtos.ErrorResponse.of("KEY_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(InvalidTtlException.class)
    public ResponseEntity<CacheDtos.ErrorResponse> handleInvalidTtl(InvalidTtlException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(CacheDtos.ErrorResponse.of("INVALID_TTL", ex.getMessage()));
    }

    @ExceptionHandler(EvictionFailedException.class)
    public ResponseEntity<CacheDtos.ErrorResponse> handleEvictionFailed(EvictionFailedException ex) {
        return ResponseEntity
                .status(HttpStatus.INSUFFICIENT_STORAGE)
                .body(CacheDtos.ErrorResponse.of("EVICTION_FAILED", ex.getMessage()));
    }
}
