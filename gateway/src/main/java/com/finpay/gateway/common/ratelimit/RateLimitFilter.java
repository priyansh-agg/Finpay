package com.finpay.gateway.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.auth.FinPayPrincipal;
import com.finpay.gateway.common.error.GlobalExceptionHandler.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Per-caller rate limiting. Added to the Spring Security chain right after the auth filter
 * (so it can key on the API key / user id). Unauthenticated requests and the
 * health/metrics/login/receiver endpoints are skipped. Over-limit → 429 + {@code Retry-After}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.startsWith("/actuator") || p.equals("/v1/auth/login") || p.startsWith("/v1/webhooks/receiver");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof FinPayPrincipal p)) {
            chain.doFilter(req, res);
            return;
        }
        if (p.merchantId() != null) {
            org.slf4j.MDC.put("merchantId", p.merchantId().toString());
        }
        RateLimiter.Result r = limiter.tryAcquire(p.subjectId());
        res.setHeader("X-RateLimit-Limit", String.valueOf(limiter.capacity()));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, r.remaining())));
        if (!r.allowed()) {
            res.setStatus(429);
            res.setHeader("Retry-After", "1");
            res.setContentType("application/json");
            mapper.writeValue(res.getWriter(), new ApiErrorResponse(
                    "RATE_LIMITED", "Too many requests", null, req.getRequestURI(), Instant.now()));
            return;
        }
        chain.doFilter(req, res);
    }
}
