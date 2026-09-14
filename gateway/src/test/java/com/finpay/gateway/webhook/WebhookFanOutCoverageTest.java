package com.finpay.gateway.webhook;

import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.refund.RefundService;
import com.finpay.gateway.settlement.SettlementService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every event the gateway publishes must reach the merchant it belongs to.
 *
 * <p>The test that would have caught the refund bug. refund.succeeded was published, the
 * outbox row was marked PUBLISHED, and no merchant ever received it — the dispatcher routed
 * by a payload field the refund payload didn't carry. WebhookDeliveryTest was green the whole
 * time because it only ever exercised payment events. So rather than test one event type,
 * this drives every flow that publishes and asserts none of them fans out to nobody.
 */
class WebhookFanOutCoverageTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired RefundService refunds;
    @Autowired SettlementService settlements;
    @Autowired WebhookEndpointRepository endpoints;
    @Autowired WebhookDispatcher dispatcher;

    @Test
    void everyPublishedEventType_producesADeliveryForTheSubscribedMerchant() {
        UUID merchantId = fixtures.newMerchant();
        subscribeToEverything(merchantId);

        Payment captured = fixtures.newCapturedPayment(merchantId, 40_000);         // payment.captured
        Order other = fixtures.newOrder(merchantId, 9_000);
        fixtures.pay(merchantId, other.getId(), "CARD", "tok_declined");             // payment.failed
        refunds.refund(merchantId, captured.getId(), 10_000L, "partial", "rf-" + UUID.randomUUID()); // refund.succeeded
        settlements.runBatch();                                                      // settlement.paid

        dispatcher.fanOut();

        List<String> published = jdbc.queryForList(
                "SELECT DISTINCT event_type FROM outbox_events ORDER BY 1", String.class);
        assertThat(published).contains("payment.captured", "payment.failed", "refund.succeeded", "settlement.paid");

        List<String> reachedNobody = jdbc.queryForList("""
                SELECT e.event_type FROM outbox_events e
                WHERE NOT EXISTS (SELECT 1 FROM webhook_deliveries d WHERE d.outbox_event_id = e.id)
                """, String.class);
        assertThat(reachedNobody).as("events that fanned out to no endpoint").isEmpty();

        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM outbox_events", String.class))
                .containsExactly("PUBLISHED");
    }

    @Test
    void anEventWithNoMerchant_isDeadLettered_notQuietlyMarkedPublished() {
        UUID merchantId = fixtures.newMerchant();
        subscribeToEverything(merchantId);

        // What a pre-V12 refund row looks like: no routing column, no merchantId in the body.
        UUID orphan = jdbc.queryForObject("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                VALUES ('REFUND', gen_random_uuid(), 'refund.succeeded', '{"refundId":"r1"}'::jsonb)
                RETURNING id""", UUID.class);

        dispatcher.fanOut();

        var row = jdbc.queryForMap("SELECT status, last_error FROM outbox_events WHERE id = ?", orphan);
        assertThat(row.get("status")).isEqualTo("DEAD");
        assertThat((String) row.get("last_error")).contains("no merchant");
    }

    private void subscribeToEverything(UUID merchantId) {
        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(merchantId);
        ep.setUrl("http://localhost:1/unused");   // fan-out only; nothing is sent in this test
        ep.setSecret("whsec_test");
        ep.setEvents(new String[0]);                // empty == every event type
        endpoints.save(ep);
    }
}
