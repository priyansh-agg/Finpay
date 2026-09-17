package com.finpay.gateway.payment;

import com.finpay.gateway.auth.CurrentCaller;
import com.finpay.gateway.auth.FinPayPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * {@code GET /v1/transactions} — paginated, filterable payment history (interview sprint
 * Day 11: the dashboard's Payments spine needs status + time range + free-text search, not
 * just a fixed page — see {@link PaymentRepository#search}). A merchant sees only their own;
 * a RISK_ANALYST / SUPER_ADMIN sees all.
 */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionsController {

    private final PaymentRepository payments;

    public TransactionsController(PaymentRepository payments) {
        this.payments = payments;
    }

    public record Page_<T>(List<T> items, int page, int size, long total) {}

    @GetMapping
    public Page_<PaymentView> list(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "25") int size,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(required = false) String q) {
        FinPayPrincipal caller = CurrentCaller.get();
        boolean analyst = caller.role().equals("RISK_ANALYST") || caller.role().equals("SUPER_ADMIN");
        PageRequest pr = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));
        PaymentState st = status == null || status.isBlank() ? null : PaymentState.valueOf(status.toUpperCase());
        Instant fromInstant = parseInstant(from);
        Instant toInstant = parseInstant(to);
        // A free-text search box can't ask for a case-sensitive UUID prefix; LIKE needs the
        // wildcard, not the raw term.
        String idPrefix = q == null || q.isBlank() ? null : q.trim().toLowerCase() + "%";

        var merchantId = analyst ? null : CurrentCaller.requireMerchantId();
        Page<Payment> result = payments.search(merchantId, st == null ? null : st.name(), fromInstant, toInstant, idPrefix, pr);

        return new Page_<>(result.map(PaymentView::of).getContent(), pr.getPageNumber(), pr.getPageSize(),
                result.getTotalElements());
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            // an HTML date input sends "2026-09-17" (no time) — treat as start-of-day UTC
            return Instant.parse(s + "T00:00:00Z");
        }
    }
}
