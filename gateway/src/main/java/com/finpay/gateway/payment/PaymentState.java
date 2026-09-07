package com.finpay.gateway.payment;

import com.finpay.gateway.common.error.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The payment lifecycle, per FinPay-Doubts-Resolved.md (Doubt 4 + Doubt 6):
 *
 * <pre>
 *   CREATED -> PENDING -> RISK_EVALUATING
 *        RISK_EVALUATING --BLOCK-->  FAILED
 *        RISK_EVALUATING --REVIEW--> PENDING_REVIEW --REJECT--> FAILED
 *        RISK_EVALUATING --REVIEW--> PENDING_REVIEW --CLEAR---> PROCESSING
 *        RISK_EVALUATING --ALLOW---> PROCESSING
 *   PROCESSING --(simulated processor)--> AUTHORIZED | FAILED
 *   AUTHORIZED --LOW risk / analyst-clear--> CAPTURED     (auto-capture = "auto-clear")
 *   AUTHORIZED --MEDIUM risk-------------->  HELD          (funds reserved, merchant unpaid)
 *   HELD --analyst CLEAR--> CAPTURED
 *   HELD --analyst REJECT / fraud--> VOIDED
 *   AUTHORIZED --> VOIDED   (authorized then killed instantly, e.g. HIGH risk)
 *   CAPTURED --> REFUNDED   (full or partial; partial stays CAPTURED until fully refunded)
 *   any non-terminal, pre-AUTHORIZED --> CANCELLED
 * </pre>
 */
public enum PaymentState {
    CREATED,
    PENDING,
    RISK_EVALUATING,
    PENDING_REVIEW,
    PROCESSING,
    AUTHORIZED,
    HELD,
    CAPTURED,
    REFUNDED,
    FAILED,
    CANCELLED,
    VOIDED;

    public static final Map<PaymentState, Set<PaymentState>> ALLOWED_TRANSITIONS;

    static {
        Map<PaymentState, Set<PaymentState>> m = new EnumMap<>(PaymentState.class);
        m.put(CREATED, Set.of(PENDING, CANCELLED));
        m.put(PENDING, Set.of(RISK_EVALUATING, CANCELLED));
        m.put(RISK_EVALUATING, Set.of(PROCESSING, PENDING_REVIEW, FAILED));
        m.put(PENDING_REVIEW, Set.of(PROCESSING, FAILED, CANCELLED));
        m.put(PROCESSING, Set.of(AUTHORIZED, FAILED));
        m.put(AUTHORIZED, Set.of(CAPTURED, HELD, VOIDED, FAILED));
        m.put(HELD, Set.of(CAPTURED, VOIDED));
        m.put(CAPTURED, Set.of(REFUNDED));
        m.put(REFUNDED, Set.of());
        m.put(FAILED, Set.of());
        m.put(CANCELLED, Set.of());
        m.put(VOIDED, Set.of());
        ALLOWED_TRANSITIONS = Map.copyOf(m);
    }

    public boolean canTransitionTo(PaymentState target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** @throws InvalidStateTransitionException if {@code this -> target} is not allowed. */
    public PaymentState assertCanTransitionTo(PaymentState target) {
        if (!canTransitionTo(target)) {
            throw new InvalidStateTransitionException(this, target);
        }
        return target;
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).isEmpty();
    }
}
