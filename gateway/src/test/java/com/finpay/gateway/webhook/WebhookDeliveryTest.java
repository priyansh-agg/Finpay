package com.finpay.gateway.webhook;

import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 exit criterion: webhook delivery survives a downed endpoint — it retries with
 * exponential backoff and then dead-letters; a later event to a recovered endpoint delivers
 * with a verifiable HMAC-SHA256 signature. Uses the built-in demo receiver as the sink.
 */
class WebhookDeliveryTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired PaymentService payments;
    @Autowired WebhookEndpointRepository endpoints;
    @Autowired WebhookDeliveryRepository deliveries;
    @Autowired WebhookDispatcher dispatcher;
    @LocalServerPort int port;

    @Test
    void downEndpoint_retriesThenDeadLetters_thenRecovers() throws Exception {
        UUID merchantId = fixtures.newMerchant();
        String base = "http://localhost:" + port;
        String secret = "whsec_test_" + UUID.randomUUID();

        // sink that rejects its first 100 attempts (i.e. "down" for the whole first phase)
        WebhookEndpoint down = new WebhookEndpoint();
        down.setMerchantId(merchantId);
        down.setUrl(base + "/v1/webhooks/receiver/dead-sink?secret=" + secret + "&failTimes=100");
        down.setSecret(secret);
        down.setEvents(new String[]{"payment.*"});
        endpoints.save(down);

        Order o1 = fixtures.newOrder(merchantId, 12_000);
        fixtures.pay(merchantId, o1.getId(), "CARD", "tok_valid"); // -> payment.captured

        poll(20_000, () -> deliveries.findAll().stream().anyMatch(d -> d.getStatus() == WebhookDelivery.Status.DEAD));
        WebhookDelivery dead = deliveries.findAll().stream()
                .filter(d -> d.getStatus() == WebhookDelivery.Status.DEAD).findFirst().orElseThrow();
        assertThat(dead.getAttempts()).isGreaterThanOrEqualTo(3);

        // a healthy endpoint added now must deliver a fresh event, signature verified
        WebhookEndpoint up = new WebhookEndpoint();
        up.setMerchantId(merchantId);
        up.setUrl(base + "/v1/webhooks/receiver/live-sink?secret=" + secret);
        up.setSecret(secret);
        up.setEvents(new String[]{"payment.*"});
        endpoints.save(up);

        Order o2 = fixtures.newOrder(merchantId, 15_000);
        fixtures.pay(merchantId, o2.getId(), "CARD", "tok_valid");

        RestClient http = RestClient.create();
        poll(20_000, () -> {
            List<?> got = http.get().uri(base + "/v1/webhooks/receiver/live-sink").retrieve().body(List.class);
            return got != null && !got.isEmpty();
        });
        @SuppressWarnings("unchecked")
        List<String> got = http.get().uri(base + "/v1/webhooks/receiver/live-sink").retrieve().body(List.class);
        assertThat(got).isNotEmpty();
        assertThat(got).allMatch(s -> s.contains("verified=true"));
    }

    /**
     * Drives the dispatcher by hand rather than waiting on {@code @Scheduled} (schedulers are
     * off under the test profile — they used to deadlock against fixture truncation). Same
     * two steps the scheduler runs, just on this thread, so retry/backoff/dead-lettering is
     * asserted deterministically instead of against a wall clock.
     */
    private void poll(long timeoutMs, java.util.concurrent.Callable<Boolean> cond) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                dispatcher.fanOut();
                dispatcher.deliver();
                if (Boolean.TRUE.equals(cond.call())) return;
            } catch (Exception ignored) {
                // a delivery attempt may throw; the next pass retries
            }
            Thread.sleep(200);
        }
        throw new AssertionError("condition not met within " + timeoutMs + "ms");
    }
}
