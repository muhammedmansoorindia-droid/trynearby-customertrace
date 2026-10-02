package com.customertrace.service.promo;

/** Raised when a promo code cannot be generated or a code fails validation. */
public class PromoCodeValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PromoCodeValidationException(String message) {
        super(message);
    }
}