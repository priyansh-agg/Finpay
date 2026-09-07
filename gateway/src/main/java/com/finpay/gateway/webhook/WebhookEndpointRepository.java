package com.finpay.gateway.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {
    List<WebhookEndpoint> findByMerchantIdAndActiveTrue(UUID merchantId);

    List<WebhookEndpoint> findByMerchantId(UUID merchantId);
}
