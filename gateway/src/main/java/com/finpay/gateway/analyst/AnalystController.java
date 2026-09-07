package com.finpay.gateway.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.payment.PaymentService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
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
                             Instant createdAt, Instant decidedAt) {}

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

    public record DecideRequest(@NotBlank String decision, String reason) {}  // decision: CLEAR | REJECT

    @PostMapping("/reviews/{id}/decide")
    public ReviewView decide(@PathVariable UUID id, @RequestBody DecideRequest req) {
        AnalystReview r = reviews.findById(id).orElseThrow(() -> ApiException.notFound("Review"));
        if (r.getQueueStatus() == AnalystReview.QueueStatus.DECIDED) {
            throw ApiException.conflict("Review already decided");
        }
        boolean clear = "CLEAR".equalsIgnoreCase(req.decision());
        paymentService.resolveReview(r.getPaymentId(), clear, "HUMAN",
                req.reason() == null ? "manual analyst decision" : req.reason());
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
            @RequestParam(required = false) String since) {
        java.time.Instant from = since == null ? null
                : java.time.OffsetDateTime.parse(since.replace(' ', '+')).toInstant();
        return metrics.compute(policy.threshold(), from);
    }

    private ReviewView toView(AnalystReview r) {
        return new ReviewView(r.getId(), r.getPaymentId(), r.getQueueStatus().name(), r.getReviewerType(),
                r.getDecision(), r.getReason(),
                r.getRiskScoreAtReview() == null ? null : r.getRiskScoreAtReview().doubleValue(),
                readJson(r.getFeaturesAtReview()), r.getCreatedAt(), r.getDecidedAt());
    }

    private Object readJson(String s) {
        try { return json.readValue(s, Object.class); } catch (Exception e) { return s; }
    }
}
