package com.customertrace.exception;

import org.springframework.http.HttpStatus;

/** Factory helpers for the common failure cases, keeping messages consistent. */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " was not found.");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * Raised when an authenticated user reaches for another tenant's data.
     *
     * <p>Deliberately reported as 404 rather than 403 in ownership lookups so the API does not
     * confirm that a record it may not see exists.
     */
    public static ApiException notAccessible() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_ACCESSIBLE",
                "That record does not exist or is not accessible to your account.");
    }
}