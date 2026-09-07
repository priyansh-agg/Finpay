package com.finpay.gateway.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.auth.CurrentCaller;
import com.finpay.gateway.auth.FinPayPrincipal;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.risk.RiskEvaluation;
import com.finpay.gateway.risk.RiskEvaluationRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Risk visibility. A merchant sees the decision/bucket/score for their own payment; a
 * RISK_ANALYST sees the full evaluation. The hidden {@code actually_fraudulent} label is
 * NEVER served here (architecture-freeze Doubt 2).
 */
@RestController
@RequestMapping("/v1/risk")
public class RiskController {

    private final RiskEvaluationRepository evaluations;
    private final PaymentRepository payments;
    private final RiskExplainer explainer;
    private final ObjectMapper json;

    public RiskController(RiskEvaluationRepository evaluations, PaymentRepository payments,
                          RiskExplainer explainer, ObjectMapper json) {
        this.evaluations = evaluations;
        this.payments = payments;
        this.explainer = explainer;
        this.json = json;
    }

    public record RiskView(UUID paymentId, String decision, String bucket, double finalScore,
                           Double ruleScore, Double mlScore, Double vectorSimilarity,
                           Object ruleFlags, Object features, Integer latencyMs) {}

    @GetMapping("/{paymentId}")
    public RiskView get(@PathVariable UUID paymentId) {
        Payment p = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        FinPayPrincipal caller = CurrentCaller.get();
        boolean analyst = caller.role().equals("RISK_ANALYST") || caller.role().equals("SUPER_ADMIN");
        if (!analyst && !p.getMerchantId().equals(caller.merchantId())) {
            throw ApiException.notFound("Payment");
        }
        RiskEvaluation e = evaluations.findFirstByPaymentIdOrderByCreatedAtDesc(paymentId)
                .orElseThrow(() -> ApiException.notFound("Risk evaluation"));

        if (!analyst) {
            // merchant view: decision + bucket + score only
            return new RiskView(paymentId, e.getDecision(), e.getBucket(), e.getFinalScore().doubleValue(),
                    null, null, null, null, null, null);
        }
        return new RiskView(paymentId, e.getDecision(), e.getBucket(), e.getFinalScore().doubleValue(),
                e.getRuleScore().doubleValue(),
                e.getMlScore() == null ? null : e.getMlScore().doubleValue(),
                e.getVectorSimilarity() == null ? null : e.getVectorSimilarity().doubleValue(),
                readJson(e.getRuleFlags()), readJson(e.getFeatures()), e.getLatencyMs());
    }

    @GetMapping("/{paymentId}/explain")
    @PreAuthorize("hasAnyRole('RISK_ANALYST','SUPER_ADMIN')")
    public RiskExplainer.Explanation explain(@PathVariable UUID paymentId,
                                             @RequestParam(defaultValue = "false") boolean refresh) {
        return explainer.explain(paymentId, refresh);
    }

    private Object readJson(String s) {
        try { return json.readValue(s, Object.class); } catch (Exception e) { return s; }
    }
}
