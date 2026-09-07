package com.finpay.gateway.payment;

import com.finpay.gateway.common.error.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Exhaustively checks the state machine: every (from, to) pair that is NOT in
 * {@code ALLOWED_TRANSITIONS} must throw, and every pair that IS must not.
 */
class PaymentStateTransitionTest {

    @Test
    void everyIllegalTransitionThrows_andEveryLegalOneDoesNot() {
        for (PaymentState from : PaymentState.values()) {
            Set<PaymentState> allowed = PaymentState.ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
            for (PaymentState to : PaymentState.values()) {
                if (allowed.contains(to)) {
                    assertThatCode(() -> from.assertCanTransitionTo(to))
                            .as("%s -> %s should be allowed", from, to)
                            .doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> from.assertCanTransitionTo(to))
                            .as("%s -> %s should be rejected", from, to)
                            .isInstanceOf(InvalidStateTransitionException.class);
                }
            }
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertThat(PaymentState.FAILED.isTerminal()).isTrue();
        assertThat(PaymentState.CANCELLED.isTerminal()).isTrue();
        assertThat(PaymentState.VOIDED.isTerminal()).isTrue();
        assertThat(PaymentState.REFUNDED.isTerminal()).isTrue();
        assertThat(PaymentState.CREATED.isTerminal()).isFalse();
        assertThat(PaymentState.AUTHORIZED.isTerminal()).isFalse();
    }

    @Test
    void happyPathIsWalkable() {
        PaymentState s = PaymentState.CREATED;
        for (PaymentState next : new PaymentState[]{
                PaymentState.PENDING, PaymentState.RISK_EVALUATING, PaymentState.PROCESSING,
                PaymentState.AUTHORIZED, PaymentState.CAPTURED, PaymentState.REFUNDED}) {
            s = s.assertCanTransitionTo(next);
        }
        assertThat(s).isEqualTo(PaymentState.REFUNDED);
    }

    @Test
    void reviewPathIsWalkable() {
        PaymentState s = PaymentState.RISK_EVALUATING;
        s = s.assertCanTransitionTo(PaymentState.PENDING_REVIEW);
        s = s.assertCanTransitionTo(PaymentState.PROCESSING);
        s = s.assertCanTransitionTo(PaymentState.AUTHORIZED);
        s = s.assertCanTransitionTo(PaymentState.HELD);
        s = s.assertCanTransitionTo(PaymentState.CAPTURED);
        assertThat(s).isEqualTo(PaymentState.CAPTURED);
    }
}
