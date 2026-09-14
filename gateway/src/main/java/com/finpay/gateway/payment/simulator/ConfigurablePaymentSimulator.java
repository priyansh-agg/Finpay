package com.finpay.gateway.payment.simulator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Doubt 1: deterministic-by-default simulator with an optional probabilistic mode.
 *
 * <p>DETERMINISTIC: a fixed token -> outcome map, so demos are reproducible in front of
 * an interviewer. PROBABILISTIC: per-method success rates, for load-testing realistic volume.
 */
@Component
public class ConfigurablePaymentSimulator implements PaymentSimulator {

    public enum Mode { DETERMINISTIC, PROBABILISTIC }

    private static final Map<String, Result> TOKEN_MAP = Map.of(
            // No fraud token: it used to plant the hidden fraud label, from a public checkout
            // menu. A card token decides what the processor answers, and nothing more.
            "tok_valid",              Result.authorized("sim_auth"),
            "tok_declined",           Result.declined("card_declined"),
            "tok_insufficient_funds", Result.declined("insufficient_funds"),
            "tok_expired",            Result.declined("expired_card")
    );

    private final Mode mode;
    private final double cardSuccessRate;
    private final double upiSuccessRate;

    public ConfigurablePaymentSimulator(
            @Value("${finpay.simulator.mode:DETERMINISTIC}") String mode,
            @Value("${finpay.simulator.card-success-rate:0.92}") double cardSuccessRate,
            @Value("${finpay.simulator.upi-success-rate:0.88}") double upiSuccessRate) {
        this.mode = Mode.valueOf(mode.toUpperCase());
        this.cardSuccessRate = cardSuccessRate;
        this.upiSuccessRate = upiSuccessRate;
    }

    @Override
    public Result process(String method, String token, long amountMinor, String currency) {
        String ref = "sim_" + UUID.randomUUID().toString().substring(0, 12);

        if (mode == Mode.DETERMINISTIC) {
            Result mapped = TOKEN_MAP.get(token);
            if (mapped == null) {
                return Result.declined("unknown_token");
            }
            return withRef(mapped, ref);
        }

        double successRate = "UPI".equalsIgnoreCase(method) ? upiSuccessRate : cardSuccessRate;
        // Known-bad tokens still deterministically decline even in probabilistic mode.
        if (TOKEN_MAP.containsKey(token) && TOKEN_MAP.get(token).outcome() == Outcome.DECLINED) {
            return withRef(TOKEN_MAP.get(token), ref);
        }
        boolean ok = ThreadLocalRandom.current().nextDouble() < successRate;
        return ok ? Result.authorized(ref) : Result.declined("processor_declined");
    }

    private Result withRef(Result r, String ref) {
        if (r.outcome() == Outcome.DECLINED) return r;
        return new Result(r.outcome(), ref, null);
    }
}
