package com.finpay.gateway.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * One structured line per request: id, method, path, status, latency, and the merchant scope
 * when known. The request id also goes into MDC so every log line during the request carries
 * it (and into the {@code X-Request-Id} response header).
 */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("finpay.request");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String reqId = UUID.randomUUID().toString().substring(0, 12);
        MDC.put("requestId", reqId);
        res.setHeader("X-Request-Id", reqId);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            // set by RateLimitFilter, which runs inside the security chain and has the principal
            String merchant = MDC.get("merchantId") == null ? "-" : MDC.get("merchantId");
            if (!req.getRequestURI().startsWith("/actuator")) {
                log.info("{} {} -> {} {}ms merchant={}",
                        req.getMethod(), req.getRequestURI(), res.getStatus(), ms, merchant);
            }
            MDC.clear();
        }
    }
}
