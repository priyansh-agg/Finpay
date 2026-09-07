package com.finpay.gateway.checkout;

import com.finpay.gateway.order.Order;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The customer-facing payment-link flow: no login required, the order id is the capability.
 */
class CheckoutFlowTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @LocalServerPort int port;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anyoneWithTheLink_canViewAndPayTheOrder_noAuth() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 25_000);

        var view = http().get().uri("/v1/checkout/{id}", order.getId()).retrieve()
                .body(CheckoutController.CheckoutView.class);
        assertThat(view).isNotNull();
        assertThat(view.amountMinor()).isEqualTo(25_000);
        assertThat(view.currency()).isEqualTo("INR");

        var payment = http().post().uri("/v1/checkout/{id}/pay", order.getId())
                .header("Idempotency-Key", "co-" + UUID.randomUUID())
                .body(Map.of("method", "CARD", "token", "tok_valid", "deviceFingerprint", "browser-1"))
                .retrieve().body(Map.class);
        assertThat(payment.get("status")).isEqualTo("CAPTURED");
    }

    @Test
    void unknownOrder_is404NotALeak() {
        assertThatThrownBy(() -> http().get().uri("/v1/checkout/{id}", UUID.randomUUID())
                .retrieve().body(CheckoutController.CheckoutView.class))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void decline_isVisibleToThePayer() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 10_000);
        var payment = http().post().uri("/v1/checkout/{id}/pay", order.getId())
                .header("Idempotency-Key", "co-" + UUID.randomUUID())
                .body(Map.of("method", "CARD", "token", "tok_declined"))
                .retrieve().body(Map.class);
        assertThat(payment.get("status")).isEqualTo("FAILED");
    }
}
