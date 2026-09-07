package com.finpay.gateway.webhook;

import com.finpay.gateway.auth.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local demo sink so you can watch webhook delivery end-to-end without an external server.
 * Point a webhook endpoint at {@code http://localhost:8080/v1/webhooks/receiver/{name}} and
 * pass {@code ?secret=} once so it can verify the HMAC signature.
 * NOT part of the product surface — permitted without auth in SecurityConfig for demo use.
 */
@RestController
@RequestMapping("/v1/webhooks/receiver")
public class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> received = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> secrets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failUntil = new ConcurrentHashMap<>();

    @PostMapping("/{name}")
    public String receive(@PathVariable String name,
                          @RequestParam(required = false) String secret,
                          @RequestParam(required = false) Integer failTimes,
                          @RequestHeader(value = "X-FinPay-Signature", required = false) String signature,
                          @RequestHeader(value = "X-FinPay-Event", required = false) String event,
                          @RequestBody String body) {
        if (secret != null) secrets.put(name, secret);
        if (failTimes != null) failUntil.putIfAbsent(name, new AtomicInteger(failTimes));

        String expectedSecret = secrets.get(name);
        boolean verified = expectedSecret != null && signature != null
                && HashUtil.constantTimeEquals(signature, "sha256=" + HashUtil.hmacSha256Hex(expectedSecret, body));

        AtomicInteger remainingFailures = failUntil.get(name);
        if (remainingFailures != null && remainingFailures.getAndDecrement() > 0) {
            log.info("receiver[{}] simulated failure for event {}", name, event);
            throw new RuntimeException("simulated receiver outage");
        }

        received.computeIfAbsent(name, k -> new ConcurrentLinkedQueue<>())
                .add("[" + event + "] verified=" + verified + " " + body);
        log.info("receiver[{}] got {} verified={}", name, event, verified);
        return "ok";
    }

    @GetMapping("/{name}")
    public Object dump(@PathVariable String name) {
        return received.getOrDefault(name, new ConcurrentLinkedQueue<>());
    }
}
