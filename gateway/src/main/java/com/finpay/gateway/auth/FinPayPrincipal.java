package com.finpay.gateway.auth;

import java.util.UUID;

/**
 * The authenticated caller. Either a merchant (API key) or a dashboard user (JWT).
 * Stored as the principal of the Spring Security Authentication.
 */
public sealed interface FinPayPrincipal permits FinPayPrincipal.MerchantCaller, FinPayPrincipal.UserCaller {

    String role();

    /** merchant id for a MerchantCaller; the user's merchant id (may be null) for a UserCaller. */
    UUID merchantId();

    String subjectId();

    record MerchantCaller(UUID merchantId, UUID apiKeyId, String role) implements FinPayPrincipal {
        @Override public String subjectId() { return "apikey:" + apiKeyId; }
    }

    record UserCaller(UUID userId, UUID merchantId, String email, String role) implements FinPayPrincipal {
        @Override public String subjectId() { return "user:" + userId; }
    }
}
