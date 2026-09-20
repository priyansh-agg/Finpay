package com.finpay.gateway.analytics;

import com.finpay.gateway.auth.CurrentCaller;
import com.finpay.gateway.auth.FinPayPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Overview-screen analytics. Every role can reach it; what it counts depends on who asks. */
@RestController
@RequestMapping("/v1/analytics")
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN','RISK_ANALYST','SUPER_ADMIN')")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /**
     * A merchant sees only their own numbers; an analyst or admin sees the platform-wide
     * roll-up — the same scoping rule {@code PaymentController.resolveViewable()} applies to a
     * single payment, just aggregated.
     */
    @GetMapping("/summary")
    public AnalyticsService.Summary summary() {
        FinPayPrincipal caller = CurrentCaller.get();
        boolean platformWide = caller.role().equals("RISK_ANALYST") || caller.role().equals("SUPER_ADMIN");
        return analytics.summarize(platformWide ? null : CurrentCaller.requireMerchantId());
    }
}
