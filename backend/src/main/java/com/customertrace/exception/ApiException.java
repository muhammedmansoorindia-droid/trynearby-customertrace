package com.customertrace.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Base type for expected, client-correctable failures.
 *
 * <p>Carrying the HTTP status on the exception keeps controllers free of status plumbing and
 * guarantees a uniform JSON error body from {@link GlobalExceptionHandler}.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** A stable machine-readable code the frontend can branch on. */
    public String getCode() {
        return code;
    }
}