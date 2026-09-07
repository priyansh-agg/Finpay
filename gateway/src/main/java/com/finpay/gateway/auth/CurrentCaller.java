package com.finpay.gateway.auth;

import com.finpay.gateway.common.error.ApiException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** Convenience accessor for the authenticated {@link FinPayPrincipal}. */
public final class CurrentCaller {
    private CurrentCaller() {}

    public static FinPayPrincipal get() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof FinPayPrincipal p)) {
            throw ApiException.unauthorized("Not authenticated");
        }
        return p;
    }

    /** The merchant scope for this request. Throws 403 for a user with no merchant (e.g. RISK_ANALYST). */
    public static UUID requireMerchantId() {
        UUID id = get().merchantId();
        if (id == null) {
            throw ApiException.forbidden("This endpoint requires a merchant-scoped caller");
        }
        return id;
    }
}
