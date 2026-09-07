package com.finpay.gateway.common.error;

import org.springframework.http.HttpStatus;

/**
 * Thrown when code attempts a payment state transition that is not in
 * {@code PaymentState.ALLOWED_TRANSITIONS}. Surfaces as HTTP 409.
 */
public class InvalidStateTransitionException extends ApiException {
    public InvalidStateTransitionException(Enum<?> from, Enum<?> to) {
        super(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Illegal transition " + from + " -> " + to);
    }
}
