package com.finpay.gateway.auth;

import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.identity.ApiKey;
import com.finpay.gateway.identity.ApiKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class ApiKeyService {

    private final ApiKeyRepository repo;
    private final String pepper;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repo, @Value("${finpay.apikey.pepper}") String pepper) {
        this.repo = repo;
        this.pepper = pepper;
    }

    public record IssuedKey(UUID id, String rawKey, String prefix) {}

    /** Generates a fresh key, persists only its HMAC hash, returns the raw value ONCE. */
    @Transactional
    public IssuedKey issue(UUID merchantId, String role, String label) {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        String raw = "fp_live_" + secret;
        String prefix = raw.substring(0, 14);

        ApiKey key = new ApiKey();
        key.setMerchantId(merchantId);
        key.setKeyPrefix(prefix);
        key.setKeyHash(HashUtil.hmacSha256Hex(pepper, raw));
        key.setRole(role == null ? "MERCHANT" : role);
        key.setLabel(label);
        repo.save(key);
        return new IssuedKey(key.getId(), raw, prefix);
    }

    /** Resolves a raw key to its record, or throws 401. Also bumps last_used_at. */
    @Transactional
    public ApiKey authenticate(String rawKey) {
        if (rawKey == null || !rawKey.startsWith("fp_live_")) {
            throw ApiException.unauthorized("Malformed API key");
        }
        ApiKey key = repo.findByKeyHash(HashUtil.hmacSha256Hex(pepper, rawKey))
                .orElseThrow(() -> ApiException.unauthorized("Unknown API key"));
        if (!key.isActive()) {
            throw ApiException.unauthorized("API key revoked");
        }
        repo.touch(key.getId(), Instant.now());
        return key;
    }
}
