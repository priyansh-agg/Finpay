package com.finpay.gateway.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.auth.HashUtil;
import com.finpay.gateway.common.config.CanonicalJson;
import com.finpay.gateway.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idempotency for unsafe writes (POST /v1/payments, POST refunds).
 *
 * <p>Flow (single transaction per request):
 * <ol>
 *   <li>Canonicalise + SHA-256 the request body.</li>
 *   <li>{@code INSERT ... ON CONFLICT (idem_key) DO NOTHING}. Postgres serialises concurrent
 *       callers on the unique index: exactly one insert returns 1 row.</li>
 *   <li>Winner (1 row): run the work, persist the response on the row, return it.</li>
 *   <li>Loser (0 rows): its INSERT blocked until the winner's transaction committed, so the
 *       row is now COMPLETED/FAILED — mirror the cached response. Same key + different body
 *       hash =&gt; 422. A stale PROCESSING row (crashed winner) =&gt; 409, retry later.</li>
 * </ol>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration STALE_AFTER = Duration.ofSeconds(30);

    private final IdempotencyKeyRepository repo;
    private final ObjectMapper mapper;
    private final CanonicalJson canonical;
    private final long ttlHours;

    public IdempotencyService(IdempotencyKeyRepository repo,
                              ObjectMapper mapper,
                              CanonicalJson canonical,
                              @Value("${finpay.idempotency.ttl-hours}") long ttlHours) {
        this.repo = repo;
        this.mapper = mapper;
        this.canonical = canonical;
        this.ttlHours = ttlHours;
    }

    public record Outcome<T>(T body, int httpStatus, boolean replayed) {}

    /**
     * @param work produces (responseObject, httpStatus). Runs only for the winning caller,
     *             inside this method's transaction.
     */
    @Transactional
    public <T> Outcome<T> run(String idemKey, UUID merchantId, String endpoint, Object requestBody,
                              Class<T> responseType, Supplier<ResponsePair<T>> work) {
        if (idemKey == null || idemKey.isBlank()) {
            throw ApiException.validation("Idempotency-Key header is required");
        }
        String requestHash = HashUtil.sha256Hex(canonicalJson(requestBody));
        Instant expiresAt = Instant.now().plus(Duration.ofHours(ttlHours));

        int inserted = repo.tryClaim(idemKey, merchantId, endpoint, requestHash, expiresAt);
        if (inserted == 1) {
            ResponsePair<T> result;
            try {
                result = work.get();
            } catch (RuntimeException ex) {
                // Mark FAILED in a way that survives this tx rollback is out of scope for the MVP;
                // the row stays PROCESSING and ages out as stale. Re-throw for the caller.
                throw ex;
            }
            repo.complete(idemKey, IdempotencyKey.Status.COMPLETED.name(), result.httpStatus(), toJson(result.body()));
            return new Outcome<>(result.body(), result.httpStatus(), false);
        }

        // Loser path.
        IdempotencyKey existing = repo.findByIdemKey(idemKey)
                .orElseThrow(() -> ApiException.conflict("Idempotency race lost but no row found; retry"));

        if (!existing.getRequestHash().equals(requestHash)) {
            throw ApiException.validation("Idempotency-Key reused with a different request body");
        }
        return switch (existing.getStatus()) {
            case COMPLETED -> new Outcome<>(fromJson(existing.getResponseBody(), responseType),
                    existing.getResponseStatus() == null ? 200 : existing.getResponseStatus(), true);
            case FAILED -> throw ApiException.conflict("The original request for this Idempotency-Key failed; use a new key");
            case PROCESSING -> {
                boolean stale = existing.getCreatedAt() != null
                        && existing.getCreatedAt().isBefore(Instant.now().minus(STALE_AFTER));
                if (stale) {
                    log.warn("Stale PROCESSING idempotency row {} — treating as retryable", idemKey);
                }
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                        "A request with this Idempotency-Key is still being processed; retry shortly");
            }
        };
    }

    public record ResponsePair<T>(T body, int httpStatus) {}

    private String canonicalJson(Object o) {
        return canonical.stringify(o);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt cached idempotency response", e);
        }
    }
}
