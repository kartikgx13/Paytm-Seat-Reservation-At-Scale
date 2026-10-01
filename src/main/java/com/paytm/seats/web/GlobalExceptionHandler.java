package com.paytm.seats.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return body(e.status(), e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "invalid_request", msg, Map.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleUnreadable(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_request", "malformed request body or parameter", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", e.getMessage(), Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", e.getMessage(), Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", "no such endpoint", Map.of());
    }

    /**
     * Lock waits that exceed lock_timeout, deadlocks and serialization failures are contention, not faults:
     * nothing was written (the transaction rolled back), so the client gets a retryable 409.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException e) {
        String state = sqlState(e);
        if (state != null && CONTENTION_STATES.contains(state)) {
            log.warn("contention decline sqlState={}", state);
            return body(HttpStatus.CONFLICT, "contention", "seat is under heavy contention, please retry", Map.of());
        }
        if (e instanceof CannotGetJdbcConnectionException) {
            Throwable root = e.getMostSpecificCause();
            if (root instanceof SQLTransientConnectionException) {
                // Pool saturated: shed load with a retryable 429 instead of failing the request.
                log.warn("connection pool exhausted, shedding request");
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("error", "too_busy");
                b.put("message", "server busy, retry shortly");
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1").body(b);
            }
            log.error("database unavailable", e);
            return body(HttpStatus.SERVICE_UNAVAILABLE, "db_unavailable", "database unavailable", Map.of());
        }
        log.error("unexpected data access error sqlState={}", state, e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "unexpected error", Map.of());
    }

    private static final Set<String> CONTENTION_STATES = Set.of(
            "55P03", // lock_not_available (lock_timeout)
            "40P01", // deadlock_detected
            "40001", // serialization_failure
            "57014"  // query_canceled (statement_timeout)
    );

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s && s.getSQLState() != null) {
                return s.getSQLState();
            }
        }
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "unexpected error", Map.of());
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message,
                                                    Map<String, Object> details) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", code);
        b.put("message", message);
        b.putAll(details);
        return ResponseEntity.status(status).body(b);
    }
}
