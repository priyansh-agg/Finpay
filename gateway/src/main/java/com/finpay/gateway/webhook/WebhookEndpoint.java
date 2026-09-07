package com.finpay.gateway.webhook;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints")
@Getter
@Setter
@NoArgsConstructor
public class WebhookEndpoint {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private String url;

    /** Shared secret; the merchant verifies X-FinPay-Signature = HMAC-SHA256(secret, body). */
    @Column(nullable = false)
    private String secret;

    @Column(columnDefinition = "text[]", nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.ARRAY)
    private String[] events = new String[0];

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public boolean subscribesTo(String eventType) {
        if (events == null || events.length == 0) return true; // empty == all
        for (String e : events) {
            if (e.equals(eventType) || (e.endsWith("*") && eventType.startsWith(e.substring(0, e.length() - 1)))) {
                return true;
            }
        }
        return false;
    }
}
