package com.customertrace.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.customertrace.service.promo.PromoCodeValidationException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Uniform JSON error handling.
 *
 * <p>Every failure the client can act on produces the same body shape:
 * {@code {status, code, message, fieldErrors?, timestamp, path}}. Internal messages are never
 * leaked for unexpected exceptions — those are logged and replaced with a generic 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest req) {
        return ResponseEntity.status(ex.getStatus())
                .body(body(ex.getStatus().value(), ex.getCode(), ex.getMessage(), null, req));
    }

    @ExceptionHandler(PromoCodeValidationException.class)
    public ResponseEntity<Map<String, Object>> handlePromo(PromoCodeValidationException ex,
                                                           HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(body(400, "PROMO_CODE_INVALID", ex.getMessage(), null, req));
    }

    /** Bean-validation failures, reported per field so forms can highlight the right input. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                 HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(body(400, "VALIDATION_FAILED", "Please correct the highlighted fields.",
                        fields, req));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex,
                                                                HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(body(400, "MALFORMED_REQUEST", "The request body could not be read.", null, req));
    }

    /**
     * A unique-constraint race (for example two concurrent redemptions of the same code).
     *
     * <p>Reported as a conflict so the caller can retry or show a friendly message rather than
     * receiving a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(DataIntegrityViolationException ex,
                                                              HttpServletRequest req) {
        log.debug("Constraint violation: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(409, "CONFLICT",
                        "That action conflicts with an existing record or was already completed.",
                        null, req));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException ex,
                                                             HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body(403, "FORBIDDEN",
                        "You do not have permission to perform this action.", null, req));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest req) {
        // The stack trace goes to the log, never to the client.
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(500, "INTERNAL_ERROR",
                        "Something went wrong. Please try again.", null, req));
    }

    private Map<String, Object> body(int status, String code, String message,
                                     Map<String, String> fieldErrors, HttpServletRequest req) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("code", code);
        map.put("message", message);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            map.put("fieldErrors", fieldErrors);
        }
        map.put("timestamp", Instant.now().toString());
        map.put("path", req == null ? null : req.getRequestURI());
        return map;
    }
}