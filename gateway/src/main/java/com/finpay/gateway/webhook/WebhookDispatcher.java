package com.finpay.gateway.webhook;

import com.finpay.gateway.outbox.OutboxEvent;
import com.finpay.gateway.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Two-stage at-least-once webhook delivery, driven by the transactional outbox:
 *
 * <ol>
 *   <li>{@link #fanOut()} turns each PENDING outbox event into one {@link WebhookDelivery}
 *       per subscribed, active endpoint, then marks the outbox row PUBLISHED.</li>
 *   <li>{@link #deliver()} hands each due delivery to {@link WebhookSender}, which POSTs it
 *       with an HMAC-SHA256 signature, retrying with exponential backoff and moving to DEAD
 *       after {@code max-attempts}.</li>
 * </ol>
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final OutboxEventRepository outbox;
    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSender sender;
    private final int batchSize;
    private final int maxAttempts;

    public WebhookDispatcher(OutboxEventRepository outbox, WebhookEndpointRepository endpoints,
                             WebhookDeliveryRepository deliveries, WebhookSender sender,
                             @Value("${finpay.outbox.batch-size:50}") int batchSize,
                             @Value("${finpay.outbox.max-attempts:8}") int maxAttempts) {
        this.outbox = outbox;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.sender = sender;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${finpay.outbox.poll-interval-ms:2000}")
    @Transactional
    public void fanOut() {
        List<OutboxEvent> due = outbox.claimDue(Instant.now(), PageRequest.of(0, batchSize));
        for (OutboxEvent ev : due) {
            try {
                UUID merchantId = ev.getMerchantId();
                if (merchantId == null) {
                    // Only pre-V12 rows can get here. Dead-letter them loudly: marking an event
                    // PUBLISHED when it reached nobody is how refund webhooks vanished.
                    ev.setStatus(OutboxEvent.Status.DEAD);
                    ev.setLastError("no merchant to route to; event was never delivered");
                    log.error("outbox event {} ({}) has no merchant — dead-lettered, not published",
                            ev.getId(), ev.getEventType());
                    continue;
                }
                for (WebhookEndpoint ep : endpoints.findByMerchantIdAndActiveTrue(merchantId)) {
                    if (!ep.subscribesTo(ev.getEventType())) continue;
                    if (deliveries.existsByEndpointIdAndOutboxEventId(ep.getId(), ev.getId())) continue;
                    WebhookDelivery d = new WebhookDelivery();
                    d.setEndpointId(ep.getId());
                    d.setOutboxEventId(ev.getId());
                    d.setEventType(ev.getEventType());
                    deliveries.save(d);
                }
                ev.setStatus(OutboxEvent.Status.PUBLISHED);
                ev.setPublishedAt(Instant.now());
            } catch (Exception e) {
                ev.setAttempts(ev.getAttempts() + 1);
                ev.setLastError(e.toString());
                ev.setNextAttemptAt(Instant.now().plusSeconds((long) Math.pow(2, Math.min(ev.getAttempts(), 10))));
                if (ev.getAttempts() >= maxAttempts) ev.setStatus(OutboxEvent.Status.DEAD);
            }
        }
    }

    @Scheduled(fixedDelayString = "${finpay.outbox.poll-interval-ms:2000}")
    public void deliver() {
        List<WebhookDelivery> due = deliveries.findDue(Instant.now(), PageRequest.of(0, batchSize));
        for (WebhookDelivery d : due) {
            try {
                sender.attempt(d.getId());
            } catch (Exception e) {
                log.warn("delivery attempt {} threw: {}", d.getId(), e.toString());
            }
        }
    }
}
