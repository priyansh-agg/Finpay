package com.finpay.gateway.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.auth.CurrentCaller;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.payment.PaymentService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Risk-analyst dashboard surface. RISK_ANALYST / SUPER_ADMIN only. */
@RestController
@RequestMapping("/v1/analyst")
@PreAuthorize("hasAnyRole('RISK_ANALYST','SUPER_ADMIN')")
public class AnalystController {

    private final AnalystReviewRepository reviews;
    private final PaymentService paymentService;
    private final AutoClearMetricsService metrics;
    private final AutoClearPolicy policy;
    private final ObjectMapper json;

    public AnalystController(AnalystReviewRepository reviews, PaymentService paymentService,
                            AutoClearMetricsService metrics, AutoClearPolicy policy, ObjectMapper json) {
        this.reviews = reviews;
        this.paymentService = paymentService;
        this.metrics = metrics;
        this.policy = policy;
        this.json = json;
    }

    public record ReviewView(UUID id, UUID paymentId, String queueStatus, String reviewerType,
                             String decision, String reason, Double riskScore, Object features,
                             Instant createdAt, Instant decidedAt, String assignedTo, Instant claimedAt) {}

    @GetMapping("/reviews")
    public List<ReviewView> queue(@RequestParam(defaultValue = "PENDING") String status,
                                  @RequestParam(defaultValue = "100") int limit) {
        var qs = AnalystReview.QueueStatus.valueOf(status.toUpperCase());
        return reviews.findQueue(qs, PageRequest.of(0, limit)).stream().map(this::toView).toList();
    }

    @GetMapping("/reviews/{id}")
    public ReviewView one(@PathVariable UUID id) {
        return toView(reviews.findById(id).orElseThrow(() -> ApiException.notFound("Review")));
    }

    /**
     * Claims a PENDING review for the calling analyst, so two analysts don't both work it and
     * so the simulated-analyst worker (interview sprint Day 8) leaves it alone. Not required
     * before {@code /decide} — that auto-claims — but lets the dashboard show who owns what.
     */
    @PostMapping("/reviews/{id}/claim")
    public ReviewView claim(@PathVariable UUID id) {
        AnalystReview r = reviews.findById(id).orElseThrow(() -> ApiException.notFound("Review"));
        if (r.getQueueStatus() == AnalystReview.QueueStatus.DECIDED) {
            throw ApiException.conflict("Review already decided");
        }
        String caller = CurrentCaller.get().subjectId();
        if (r.getAssignedTo() != null && !r.getAssignedTo().equals(caller)) {
            throw ApiException.conflict("Review already claimed by " + r.getAssignedTo());
        }
        r.setAssignedTo(caller);
        r.setClaimedAt(Instant.now());
        reviews.save(r);
        return toView(r);
    }

    public record DecideRequest(@NotBlank String decision, String reason) {}  // decision: CLEAR | REJECT

    @PostMapping("/reviews/{id}/decide")
    public ReviewView decide(@PathVariable UUID id, @RequestBody DecideRequest req) {
        AnalystReview r = reviews.findById(id).orElseThrow(() -> ApiException.notFound("Review"));
        if (r.getQueueStatus() == AnalystReview.QueueStatus.DECIDED) {
            throw ApiException.conflict("Review already decided");
        }
        String caller = CurrentCaller.get().subjectId();
        if (r.getAssignedTo() != null && !r.getAssignedTo().equals(caller)) {
            throw ApiException.conflict("Review is claimed by " + r.getAssignedTo());
        }
        boolean clear = "CLEAR".equalsIgnoreCase(req.decision());
        paymentService.resolveReview(r.getPaymentId(), clear, "HUMAN",
                req.reason() == null ? "manual analyst decision" : req.reason());
        if (r.getAssignedTo() == null) {
            r.setAssignedTo(caller);
            r.setClaimedAt(Instant.now());
        }
        r.setQueueStatus(AnalystReview.QueueStatus.DECIDED);
        r.setReviewerType("HUMAN");
        r.setDecision(clear ? "CLEAR" : "REJECT");
        r.setReason(req.reason());
        r.setDecidedAt(Instant.now());
        reviews.save(r);
        return toView(r);
    }

    @GetMapping("/metrics/autoclear")
    public AutoClearMetricsService.Metrics autoClearMetrics(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) UUID runId) {
        java.time.Instant from = since == null ? null
                : java.time.OffsetDateTime.parse(since.replace(' ', '+')).toInstant();
        return metrics.compute(policy.threshold(), from, runId);
    }

    /**
     * Precision/recall/false-positive-rate against the hidden ground truth (interview sprint
     * Day 10) — the whole pipeline's effectiveness, review-based rejection and settlement
     * write-off (Day 9) alike, not just the Auto-Clear Engine. Optionally scoped to one
     * simulation run.
     */
    @GetMapping("/metrics/fraud")
    public AutoClearMetricsService.FraudMetrics fraudMetrics(@RequestParam(required = false) UUID runId) {
        return metrics.computeFraudMetrics(runId);
    }

    private ReviewView toView(AnalystReview r) {
        return new ReviewView(r.getId(), r.getPaymentId(), r.getQueueStatus().name(), r.getReviewerType(),
                r.getDecision(), r.getReason(),
                r.getRiskScoreAtReview() == null ? null : r.getRiskScoreAtReview().doubleValue(),
                readJson(r.getFeaturesAtReview()), r.getCreatedAt(), r.getDecidedAt(),
                r.getAssignedTo(), r.getClaimedAt());
    }

    private Object readJson(String s) {
        try { return json.readValue(s, Object.class); } catch (Exception e) { return s; }
    }
}
