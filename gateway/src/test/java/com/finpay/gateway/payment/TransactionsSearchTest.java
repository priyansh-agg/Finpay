package com.finpay.gateway.payment;

import com.finpay.gateway.auth.FinPayPrincipal;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * List ergonomics for the dashboard's Payments spine (interview sprint Day 11 — the audit's
 * own C.3 item 5: every list needs status + time range + free-text search + pagination, not a
 * fixed 15-row table with a manual refresh button).
 */
class TransactionsSearchTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired TransactionsController controller;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void statusFilter_returnsOnlyMatchingPayments() {
        UUID merchantId = fixtures.newMerchant();
        fixtures.newCapturedPayment(merchantId, 10_000);
        fixtures.pay(merchantId, fixtures.newOrder(merchantId, 20_000).getId(), "CARD", "tok_declined");
        loginAsMerchant(merchantId);

        var captured = controller.list(0, 25, "CAPTURED", null, null, null);
        var failed = controller.list(0, 25, "FAILED", null, null, null);

        assertThat(captured.items()).hasSize(1).allSatisfy(p -> assertThat(p.status()).isEqualTo("CAPTURED"));
        assertThat(failed.items()).hasSize(1).allSatisfy(p -> assertThat(p.status()).isEqualTo("FAILED"));
    }

    @Test
    void merchantCaller_onlySeesItsOwnPayments() {
        UUID m1 = fixtures.newMerchant();
        UUID m2 = fixtures.newMerchant();
        fixtures.newCapturedPayment(m1, 10_000);
        fixtures.newCapturedPayment(m2, 20_000);
        loginAsMerchant(m1);

        var result = controller.list(0, 25, null, null, null, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.total()).isEqualTo(1);
    }

    @Test
    void analystCaller_seesEveryMerchantsPayments() {
        UUID m1 = fixtures.newMerchant();
        UUID m2 = fixtures.newMerchant();
        fixtures.newCapturedPayment(m1, 10_000);
        fixtures.newCapturedPayment(m2, 20_000);
        loginAsAdmin("SUPER_ADMIN");

        var result = controller.list(0, 25, null, null, null, null);

        assertThat(result.total()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void idPrefixSearch_matchesEitherThePaymentOrTheOrderId() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 10_000);
        fixtures.newCapturedPayment(merchantId, 20_000); // noise — should not match
        loginAsMerchant(merchantId);

        String prefix = p.getId().toString().substring(0, 8);
        var byPaymentId = controller.list(0, 25, null, null, null, prefix);
        var byOrderId = controller.list(0, 25, null, null, null, p.getOrderId().toString().substring(0, 8));

        assertThat(byPaymentId.items()).extracting(PaymentView::id).containsExactly(p.getId());
        assertThat(byOrderId.items()).extracting(PaymentView::id).containsExactly(p.getId());
    }

    @Test
    void timeRange_excludesPaymentsOutsideTheWindow() {
        UUID merchantId = fixtures.newMerchant();
        fixtures.newCapturedPayment(merchantId, 10_000);
        loginAsMerchant(merchantId);

        Instant future = Instant.now().plusSeconds(3600);
        var nothingYet = controller.list(0, 25, null, future.toString(), null, null);
        var everything = controller.list(0, 25, null, null, future.toString(), null);

        assertThat(nothingYet.items()).as("from the future — nothing captured yet").isEmpty();
        assertThat(everything.items()).as("to the future — includes what exists now").hasSize(1);
    }

    @Test
    void pagination_splitsResultsAcrossPages() {
        UUID merchantId = fixtures.newMerchant();
        for (int i = 0; i < 5; i++) fixtures.newCapturedPayment(merchantId, 10_000 + i);
        loginAsMerchant(merchantId);

        var page0 = controller.list(0, 2, null, null, null, null);
        var page1 = controller.list(1, 2, null, null, null, null);

        assertThat(page0.items()).hasSize(2);
        assertThat(page1.items()).hasSize(2);
        assertThat(page0.total()).isEqualTo(5);
        List<UUID> ids0 = page0.items().stream().map(PaymentView::id).toList();
        List<UUID> ids1 = page1.items().stream().map(PaymentView::id).toList();
        assertThat(ids0).doesNotContainAnyElementsOf(ids1);
    }

    private void loginAsMerchant(UUID merchantId) {
        var principal = new FinPayPrincipal.MerchantCaller(merchantId, UUID.randomUUID(), "MERCHANT_ADMIN");
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void loginAsAdmin(String role) {
        var principal = new FinPayPrincipal.UserCaller(UUID.randomUUID(), null, "admin@test.local", role);
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
