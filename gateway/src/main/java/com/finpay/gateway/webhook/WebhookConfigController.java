package com.finpay.gateway.webhook;

import com.finpay.gateway.auth.CurrentCaller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/webhooks/config")
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN')")
public class WebhookConfigController {

    private final WebhookEndpointRepository endpoints;
    private final SecureRandom random = new SecureRandom();

    public WebhookConfigController(WebhookEndpointRepository endpoints) {
        this.endpoints = endpoints;
    }

    public record CreateWebhookRequest(@NotBlank String url, List<String> events) {}

    public record WebhookResponse(UUID id, String url, List<String> events, boolean active,
                                  String secret, Instant createdAt) {}

    @PostMapping
    public WebhookResponse create(@RequestBody @Valid CreateWebhookRequest req) {
        UUID merchantId = CurrentCaller.requireMerchantId();
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(merchantId);
        ep.setUrl(req.url());
        ep.setSecret(secret);
        ep.setEvents(req.events() == null ? new String[0] : req.events().toArray(String[]::new));
        endpoints.save(ep);
        return new WebhookResponse(ep.getId(), ep.getUrl(),
                req.events() == null ? List.of() : req.events(), ep.isActive(), secret, ep.getCreatedAt());
    }

    @GetMapping
    public List<WebhookResponse> list() {
        return endpoints.findByMerchantId(CurrentCaller.requireMerchantId()).stream()
                .map(ep -> new WebhookResponse(ep.getId(), ep.getUrl(),
                        ep.getEvents() == null ? List.of() : List.of(ep.getEvents()),
                        ep.isActive(), "whsec_***", ep.getCreatedAt()))
                .toList();
    }

    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable UUID id) {
        UUID merchantId = CurrentCaller.requireMerchantId();
        endpoints.findById(id)
                .filter(e -> e.getMerchantId().equals(merchantId))
                .ifPresent(e -> { e.setActive(false); endpoints.save(e); });
    }
}
