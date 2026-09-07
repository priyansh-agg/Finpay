package com.finpay.gateway.payment.simulator;

/**
 * Abstraction over "the payment processor". CARD and UPI both go through this, so
 * swapping in a real Razorpay/Stripe adapter later is a drop-in replacement.
 */
public interface PaymentSimulator {

    enum Outcome { AUTHORIZED, DECLINED }

    record Result(Outcome outcome, String processorRef, String declineReason, boolean processorFlagged) {
        public static Result authorized(String ref) {
            return new Result(Outcome.AUTHORIZED, ref, null, false);
        }
        public static Result authorizedButFlagged(String ref) {
            return new Result(Outcome.AUTHORIZED, ref, null, true);
        }
        public static Result declined(String reason) {
            return new Result(Outcome.DECLINED, null, reason, false);
        }
    }

    Result process(String method, String token, long amountMinor, String currency);
}
