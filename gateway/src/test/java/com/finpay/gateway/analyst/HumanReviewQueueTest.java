package com.finpay.gateway.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.auth.FinPayPrincipal;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.groundtruth.FraudGroundTruthRepository;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.risk.RiskEvaluationRepository;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The queue-with-a-human part of interview sprint Day 8: a review the auto-clear proposer
 * declines is claimable, ownership is real (a second analyst can't also work it, and the
 * demo-only {@link SimulatedAnalystDecider} steps aside once a human has it), and a real human's
 * decision — never the demo bot's — is the baseline {@code manualReviewReductionPct} divides
 * by. Before this, every review was decided by the scheduler itself within its own poll
 * interval, so there was no human-reviewed baseline to measure "review reduction" against.
 */
class HumanReviewQueueTest extends AbstractIntegrationTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Autowired TestFixtures fixtures;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRepo;
    @Autowired AnalystReviewRepository reviews;
    @Autowired AnalystController controller;
    @Autowired ReviewDecider decider;
    @Autowired SimulatedAnalystDecider simulatedAnalyst;
    @Autowired SimulatedAnalystWorker worker;
    @Autowired AutoClearMetricsService metrics;
    @Autowired AutoClearPolicy policy;
    @Autowired RiskEvaluationRepository evaluations;
    @Autowired FraudGroundTruthRepository groundTruth;
    @Autowired ReferenceAnalyst referenceAnalyst;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSecondAnalyst_cannotClaim_aReviewAlreadyClaimed() {
        UUID reviewId = flagAReview();

        loginAs(ALICE);
        controller.claim(reviewId);
        assertThat(reviews.findById(reviewId).orElseThrow().getAssignedTo()).isEqualTo(subject(ALICE));

        loginAs(BOB);
        assertThatThrownBy(() -> controller.claim(reviewId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(subject(ALICE));
    }

    @Test
    void claimingTwice_bySameAnalyst_isNotAConflict() {
        UUID reviewId = flagAReview();
        loginAs(ALICE);
        controller.claim(reviewId);
        controller.claim(reviewId); // idempotent, must not throw
        assertThat(reviews.findById(reviewId).orElseThrow().getAssignedTo()).isEqualTo(subject(ALICE));
    }

    @Test
    void decide_byAnAnalystOtherThanWhoClaimed_isRefused() {
        UUID reviewId = flagAReview();
        loginAs(ALICE);
        controller.claim(reviewId);

        loginAs(BOB);
        assertThatThrownBy(() -> controller.decide(reviewId,
                new AnalystController.DecideRequest("CLEAR", "looks fine")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("claimed by");
    }

    @Test
    void aHumanDeciding_withoutClaimingFirst_autoClaimsAndDecides() {
        UUID reviewId = flagAReview();
        loginAs(ALICE);

        var view = controller.decide(reviewId, new AnalystController.DecideRequest("REJECT", "manual reject"));

        assertThat(view.queueStatus()).isEqualTo("DECIDED");
        assertThat(view.reviewerType()).isEqualTo("HUMAN");
        assertThat(view.assignedTo()).isEqualTo(subject(ALICE));
        assertThat(paymentRepo.findById(view.paymentId()).orElseThrow().getStatus().name())
                .isIn("VOIDED", "FAILED");
    }

    /**
     * Regression guard for a real bug this feature shipped with once: {@code drain()} used to
     * call its OWN {@code decide()} method directly (self-invocation). Spring's
     * {@code @Transactional} is applied by a proxy that only intercepts calls arriving from
     * OUTSIDE the bean, so that self-call silently ran with no transaction — the payment side
     * (a call to the separate {@code PaymentService} bean) committed fine, but the review row's
     * own field changes, made on a plain in-memory object after that, were never flushed. A
     * live demo run surfaced this: dozens of payments ended up VOIDED/CAPTURED while their
     * reviews stayed PENDING forever, invisible to every "still pending" count. Splitting the
     * decision into the separately-injected {@link SimulatedAnalystDecider} bean fixes it —
     * this test drives the real {@code @Scheduled} path and checks the review row itself, not
     * just the payment.
     */
    @Test
    void drain_actuallyPersistsTheReviewsOwnDecision_notJustThePaymentSideEffect() {
        UUID reviewId = flagAReview();

        worker.drain(); // the real @Scheduled entrypoint; latency-seconds is 0 in tests

        AnalystReview review = reviews.findById(reviewId).orElseThrow();
        assertThat(review.getQueueStatus()).as("drain() must flush the review's OWN decision, not just the payment's")
                .isEqualTo(AnalystReview.QueueStatus.DECIDED);
        assertThat(review.getReviewerType()).isEqualTo("SIMULATED_ANALYST");
        assertThat(review.getDecision()).isNotNull();
        assertThat(review.getDecidedAt()).isNotNull();
    }

    @Test
    void simulatedAnalyst_neverDecides_aReviewAHumanHasClaimed() {
        UUID reviewId = flagAReview();
        loginAs(ALICE);
        controller.claim(reviewId);

        simulatedAnalyst.decide(reviewId); // must no-op: assignedTo is already set

        var review = reviews.findById(reviewId).orElseThrow();
        assertThat(review.getQueueStatus()).as("still PENDING — the claim, not the bot, owns it now")
                .isEqualTo(AnalystReview.QueueStatus.PENDING);
        assertThat(review.getReviewerType()).isNull();
    }

    @Test
    void accuracyZero_deterministicallyFlipsEveryDecision_awayFromTheRulesOwnVerdict() {
        UUID reviewId = flagAReview();
        AnalystReview review = reviews.findById(reviewId).orElseThrow();
        Payment payment = paymentRepo.findById(review.getPaymentId()).orElseThrow();
        AnalystReviewInputs in = AnalystReviewInputs.resolve(payment, evaluations, groundTruth, json);
        ReferenceAnalyst.Verdict trueVerdict = referenceAnalyst.decide(in.riskScore, in.featuresOrEmpty(), in.priorFraud);
        boolean trueClear = trueVerdict.decision() == ReferenceAnalyst.Decision.CLEAR;

        // Not a Spring bean, so its own @Transactional does nothing — run it inside a real
        // transaction (the same one the JPA repositories join) so the decision actually flushes.
        SimulatedAnalystDecider neverAccurate = new SimulatedAnalystDecider(
                reviews, paymentRepo, payments, evaluations, groundTruth, referenceAnalyst, json, 0.0);
        tx.execute(status -> { neverAccurate.decide(reviewId); return null; });

        assertThat(reviews.findById(reviewId).orElseThrow().getDecision())
                .as("accuracy 0.0 flips every decision away from the deterministic rule's own verdict")
                .isEqualTo(trueClear ? "REJECT" : "CLEAR");
    }

    @Test
    void manualReviewReductionPct_isNull_untilAHumanDecidesOne_thenExcludesTheDemoBot() {
        Instant since = Instant.now().minusSeconds(5);

        UUID botDecided = flagAReview();
        simulatedAnalyst.decide(botDecided); // demo bot, not a human

        var before = metrics.compute(policy.threshold(), since);
        assertThat(before.humanReviewed()).isZero();
        assertThat(before.simulatedAnalystReviewed()).isEqualTo(1);
        assertThat(before.manualReviewReductionPct()).as("no human baseline exists yet").isNull();

        UUID humanDecided = flagAReview();
        loginAs(ALICE);
        controller.decide(humanDecided, new AnalystController.DecideRequest("REJECT", "manual"));

        var after = metrics.compute(policy.threshold(), since);
        assertThat(after.humanReviewed()).isEqualTo(1);
        assertThat(after.simulatedAnalystReviewed())
                .as("the bot's decision didn't disappear, it just isn't counted as human").isEqualTo(1);
        assertThat(after.manualReviewReductionPct())
                .as("computable now that a real human baseline exists").isNotNull();
    }

    private void loginAs(UUID userId) {
        var principal = new FinPayPrincipal.UserCaller(userId, null, userId + "@test.local", "RISK_ANALYST");
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_RISK_ANALYST")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String subject(UUID userId) {
        return "user:" + userId;
    }

    /** New device + new IP + a non-trivial amount: rules fire, held for review, and the
     *  proposer (no ML sidecar in tests) always declines — a review that's genuinely PENDING. */
    private UUID flagAReview() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 900_000);
        Payment p = tx.execute(s -> payments.createPayment(new PaymentService.CreatePaymentCommand(
                merchantId, order.getId(), "CARD", "tok_valid", "brand-new-device-" + UUID.randomUUID(),
                "203.0.113." + (1 + (Math.abs(UUID.randomUUID().hashCode()) % 250)))));
        AnalystReview review = reviews.findFirstByPaymentIdOrderByCreatedAtDesc(p.getId()).orElseThrow();
        decider.decide(review.getId());
        return review.getId();
    }
}
