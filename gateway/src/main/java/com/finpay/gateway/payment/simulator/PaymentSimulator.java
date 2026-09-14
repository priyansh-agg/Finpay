package com.finpay.gateway.payment.simulator;

/**
 * Abstraction over "the payment processor". CARD and UPI both go through this, so
 * swapping in a real Razorpay/Stripe adapter later is a drop-in replacement.
 *
 * <p>A processor answers one question — authorised or declined — and nothing else. It does
 * not decide whether a transaction is fraud; that is the risk engine's estimate and, in this
 * sandbox, the simulation harness's hidden ground truth.
 */
public interface PaymentSimulator {

    enum Outcome { AUTHORIZED, DECLINED }

    record Result(Outcome outcome, String processorRef, String declineReason) {
        public static Result authorized(String ref) {
            return new Result(Outcome.AUTHORIZED, ref, null);
        }
        public static Result declined(String reason) {
            return new Result(Outcome.DECLINED, null, reason);
        }
    }

    Result process(String method, String token, long amountMinor, String currency);
}
