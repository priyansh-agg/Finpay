package com.finpay.gateway.checkout;

import com.finpay.gateway.order.Order;
import com.finpay.gateway.order.OrderService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The customer-facing payment-link flow: no login required, the order id is the capability.
 * Runs against the real risk engine and production risk config.
 */
class CheckoutFlowTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired OrderService orders;
    @LocalServerPort int port;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * This test used to assert CAPTURED. It only passed because the test profile raised
     * auto-capture-max from production's 6 to 15. Under production config a first-time
     * customer — new device, new IP, account minutes old — trips NEW_EVERYTHING, scores in
     * the LOW bucket but above the auto-capture ceiling, and is authorised and HELD for
     * review. That is the designed behaviour (architecture-freeze Doubt 4), so it is what
     * the test now pins.
     */
    @Test
    void aFirstTimeCustomer_paysThroughTheLink_andIsAuthorisedButHeld() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 25_000);

        var view = http().get().uri("/v1/checkout/{id}", order.getId()).retrieve()
                .body(CheckoutController.CheckoutView.class);
        assertThat(view).isNotNull();
        assertThat(view.amountMinor()).isEqualTo(25_000);
        assertThat(view.currency()).isEqualTo("INR");

        ResponseEntity<Map> resp = pay(order, "browser-1");
        assertThat(resp.getStatusCode().value()).as("202 — accepted, not yet captured").isEqualTo(202);
        assertThat(resp.getBody()).containsEntry("status", "HELD").containsEntry("riskBucket", "LOW");
    }

    @Test
    void aReturningCustomer_onAKnownDeviceAndIp_isAutoCaptured() {
        UUID merchantId = fixtures.newMerchant();
        String customerRef = "returning-" + UUID.randomUUID();

        Order first = orders.create(merchantId, customerRef, customerRef + "@test.local", 25_000, "INR", "rcpt-1", Map.of());
        assertThat(pay(first, "browser-1").getBody()).containsEntry("status", "HELD");

        // Same customer, same device, same IP: nothing new about them any more.
        Order second = orders.create(merchantId, customerRef, customerRef + "@test.local", 25_000, "INR", "rcpt-2", Map.of());
        ResponseEntity<Map> resp = pay(second, "browser-1");

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody()).containsEntry("status", "CAPTURED");
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

    private ResponseEntity<Map> pay(Order order, String device) {
        return http().post().uri("/v1/checkout/{id}/pay", order.getId())
                .header("Idempotency-Key", "co-" + UUID.randomUUID())
                .body(Map.of("method", "CARD", "token", "tok_valid", "deviceFingerprint", device))
                .retrieve().toEntity(Map.class);
    }
}
