package com.finpay.gateway.webhook;

import com.finpay.gateway.auth.HashUtil;
import com.finpay.gateway.outbox.OutboxEvent;
import com.finpay.gateway.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.UUID;

/** Performs one webhook delivery attempt in its own transaction (separate bean so the proxy applies). */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final OutboxEventRepository outbox;
    private final com.finpay.gateway.common.metrics.FinPayMetrics metrics;
    private final RestClient http;
    private final int maxAttempts;
    private final long backoffBaseMs;

    public WebhookSender(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints,
                         OutboxEventRepository outbox, com.finpay.gateway.common.metrics.FinPayMetrics metrics,
                         @Value("${finpay.outbox.max-attempts:8}") int maxAttempts,
                         @Value("${finpay.outbox.backoff-base-ms:2000}") long backoffBaseMs) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.outbox = outbox;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Transactional
    public void attempt(UUID deliveryId) {
        WebhookDelivery d = deliveries.findById(deliveryId).orElse(null);
        if (d == null || d.getStatus() == WebhookDelivery.Status.DELIVERED || d.getStatus() == WebhookDelivery.Status.DEAD) {
            return;
        }
        WebhookEndpoint ep = endpoints.findById(d.getEndpointId()).orElse(null);
        OutboxEvent ev = outbox.findById(d.getOutboxEventId()).orElse(null);
        if (ep == null || ev == null) {
            d.setStatus(WebhookDelivery.Status.DEAD);
            d.setLastError("endpoint or event missing");
            return;
        }

        String body = ev.getPayload();
        String signature = "sha256=" + HashUtil.hmacSha256Hex(ep.getSecret(), body);
        d.setAttempts(d.getAttempts() + 1);
        try {
            var resp = http.post().uri(ep.getUrl())
                    .header("Content-Type", "application/json")
                    .header("X-FinPay-Event", ev.getEventType())
                    .header("X-FinPay-Delivery", d.getId().toString())
                    .header("X-FinPay-Signature", signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            d.setLastStatusCode(resp.getStatusCode().value());
            d.setLastError(null);
            d.setStatus(WebhookDelivery.Status.DELIVERED);
            d.setDeliveredAt(Instant.now());
            metrics.webhookDelivery("delivered");
        } catch (RestClientResponseException e) {
            d.setLastStatusCode(e.getStatusCode().value());
            failOrDead(d, "HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            failOrDead(d, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void failOrDead(WebhookDelivery d, String error) {
        d.setLastError(error);
        if (d.getAttempts() >= maxAttempts) {
            d.setStatus(WebhookDelivery.Status.DEAD);
            metrics.webhookDelivery("dead");
            log.warn("webhook delivery {} DEAD after {} attempts: {}", d.getId(), d.getAttempts(), error);
        } else {
            d.setStatus(WebhookDelivery.Status.FAILED);
            d.setNextAttemptAt(Instant.now().plusMillis(backoffMs(d.getAttempts())));
            metrics.webhookDelivery("failed");
        }
    }

    /** Exponential backoff: base · 2^(attempt-1), capped at 1h. */
    private long backoffMs(int attempts) {
        return Math.min(3_600_000L, (long) (backoffBaseMs * Math.pow(2, Math.min(attempts - 1, 12))));
    }
}
