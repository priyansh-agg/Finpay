package com.finpay.gateway.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.auth.HashUtil;
import com.finpay.gateway.common.config.CanonicalJson;
import com.finpay.gateway.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idempotency for unsafe writes (POST /v1/payments, hosted checkout).
 *
 * <p>Keys are scoped per merchant: the key string is picked by the caller, so
 * {@code (merchant_id, idem_key)} is the unit of uniqueness, not {@code idem_key} (V10).
 *
 * <p>Flow — claim, work and response caching all share ONE transaction:
 * <ol>
 *   <li>Canonicalise + SHA-256 the request body.</li>
 *   <li>{@code INSERT ... ON CONFLICT (merchant_id, idem_key) DO NOTHING}. Postgres
 *       serialises concurrent callers on the unique index: exactly one insert returns a row.</li>
 *   <li>Winner: run the work, persist the response on the row, return it.</li>
 *   <li>Loser: its INSERT blocked until the winner's transaction resolved, so the row is
 *       now COMPLETED (mirror the cached response) or FAILED (see below). Same key with a
 *       different endpoint or body hash =&gt; 422.</li>
 * </ol>
 *
 * <p><b>Failure is retryable under the same key.</b> When the work throws, its transaction
 * rolls back and nothing took effect — so the honest answer to a client retrying that key is
 * to run it again, not to tell them to pick a new key. Telling them to pick a new key is
 * precisely wrong in the situation idempotency keys exist for: the client saw an ambiguous
 * outcome and does not know whether the original applied.
 *
 * <p><b>Keys expire.</b> After {@code finpay.idempotency.ttl-hours} a key is free again and
 * the next request under it is treated as new — which is what a stated TTL promises.
 *
 * <p>A FAILED marker is written from a separate transaction once the failed attempt has
 * finished rolling back, which is why {@link #run} owns its transaction programmatically
 * instead of being {@code @Transactional} — the ordering (roll back, THEN record) is the
 * whole point, and a declarative annotation cannot express it.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository repo;
    private final ObjectMapper mapper;
    private final CanonicalJson canonical;
    private final TransactionTemplate tx;
    private final long ttlHours;

    public IdempotencyService(IdempotencyKeyRepository repo,
                              ObjectMapper mapper,
                              CanonicalJson canonical,
                              PlatformTransactionManager txManager,
                              @Value("${finpay.idempotency.ttl-hours}") long ttlHours) {
        this.repo = repo;
        this.mapper = mapper;
        this.canonical = canonical;
        this.tx = new TransactionTemplate(txManager);
        this.ttlHours = ttlHours;
    }

    public record Outcome<T>(T body, int httpStatus, boolean replayed) {}

    public record ResponsePair<T>(T body, int httpStatus) {}

    /**
     * @param work produces (responseObject, httpStatus). Runs only for the caller that owns
     *             the key, inside this method's transaction.
     */
    public <T> Outcome<T> run(String idemKey, UUID merchantId, String endpoint, Object requestBody,
                              Class<T> responseType, Supplier<ResponsePair<T>> work) {
        if (idemKey == null || idemKey.isBlank()) {
            throw ApiException.validation("Idempotency-Key header is required");
        }
        String requestHash = HashUtil.sha256Hex(canonical.stringify(requestBody));
        Instant expiresAt = Instant.now().plus(Duration.ofHours(ttlHours));

        try {
            return tx.execute(status ->
                    attempt(idemKey, merchantId, endpoint, requestHash, expiresAt, responseType, work));
        } catch (WorkFailed failure) {
            // The transaction above has fully rolled back by now — including the claim row.
            // Only here is it safe to write the FAILED marker in a transaction of its own.
            RuntimeException original = failure.unwrap();
            try {
                repo.recordFailure(idemKey, merchantId, endpoint, requestHash, expiresAt);
            } catch (RuntimeException markerError) {
                // Losing the marker is survivable (the retry simply finds no row); losing the
                // caller's real error is not. Keep the original on top.
                original.addSuppressed(markerError);
                log.error("could not record failure for Idempotency-Key {} (merchant {})", idemKey, merchantId, markerError);
            }
            log.warn("idempotent work failed for key {} (merchant {}): {} — the same key may be retried",
                    idemKey, merchantId, original.toString());
            throw original;
        }
        // Anything else thrown out of attempt() is a rejection of THIS request (unknown key
        // state, mismatched body, key still in flight) rather than a failure of the work.
        // Those must not leave a FAILED marker: the key may well belong to a completed
        // request whose cached response another caller still needs.
    }

    /** Claim-or-mirror plus the work, all in one transaction. */
    private <T> Outcome<T> attempt(String idemKey, UUID merchantId, String endpoint, String requestHash,
                                   Instant expiresAt, Class<T> responseType, Supplier<ResponsePair<T>> work) {
        if (repo.tryClaim(idemKey, merchantId, endpoint, requestHash, expiresAt) == 1) {
            return execute(idemKey, merchantId, work);
        }

        IdempotencyKey existing = repo.findByMerchantIdAndIdemKey(merchantId, idemKey)
                .orElseThrow(() -> ApiException.conflict("Idempotency race lost but no row found; retry"));

        // Past its TTL the key is free: this is a new request, whatever it carries.
        if (existing.getExpiresAt() != null && existing.getExpiresAt().isBefore(Instant.now())) {
            return takeOver(idemKey, merchantId, endpoint, requestHash, expiresAt, responseType, work);
        }

        assertSameRequest(existing, endpoint, requestHash);

        return switch (existing.getStatus()) {
            case COMPLETED -> replay(existing, responseType);

            // A previous attempt rolled back, so nothing took effect and this key is safe to
            // run again.
            case FAILED -> takeOver(idemKey, merchantId, endpoint, requestHash, expiresAt, responseType, work);

            // Another caller holds the key right now. Callers racing the SAME key normally
            // block on the unique index above and never land here.
            case PROCESSING -> throw inProgress();
        };
    }

    /**
     * Take over a reusable key and run the work; if another caller took it first, answer the
     * way a loser of the original claim would — with the winner's cached response.
     */
    private <T> Outcome<T> takeOver(String idemKey, UUID merchantId, String endpoint, String requestHash,
                                    Instant expiresAt, Class<T> responseType, Supplier<ResponsePair<T>> work) {
        if (repo.reclaim(merchantId, idemKey, endpoint, requestHash, expiresAt) == 1) {
            log.info("Idempotency-Key {} (merchant {}) taken over for a fresh attempt", idemKey, merchantId);
            return execute(idemKey, merchantId, work);
        }
        // Our UPDATE waited on the winner's row lock, so the winner has resolved by now.
        IdempotencyKey winner = repo.findByMerchantIdAndIdemKey(merchantId, idemKey).orElseThrow(IdempotencyService::inProgress);
        if (winner.getStatus() != IdempotencyKey.Status.COMPLETED) {
            throw inProgress();
        }
        assertSameRequest(winner, endpoint, requestHash);
        return replay(winner, responseType);
    }

    private static void assertSameRequest(IdempotencyKey row, String endpoint, String requestHash) {
        if (!endpoint.equals(row.getEndpoint())) {
            throw ApiException.validation(
                    "Idempotency-Key was already used on " + row.getEndpoint() + "; use a distinct key per request");
        }
        if (!row.getRequestHash().equals(requestHash)) {
            throw ApiException.validation("Idempotency-Key reused with a different request body");
        }
    }

    private <T> Outcome<T> replay(IdempotencyKey row, Class<T> responseType) {
        return new Outcome<>(fromJson(row.getResponseBody(), responseType),
                row.getResponseStatus() == null ? 200 : row.getResponseStatus(), true);
    }

    private <T> Outcome<T> execute(String idemKey, UUID merchantId, Supplier<ResponsePair<T>> work) {
        ResponsePair<T> result;
        try {
            result = work.get();
        } catch (RuntimeException ex) {
            // Tag it so run() can tell "the work blew up" from "this request was rejected".
            throw new WorkFailed(ex);
        }
        repo.complete(merchantId, idemKey, IdempotencyKey.Status.COMPLETED.name(),
                result.httpStatus(), toJson(result.body()));
        return new Outcome<>(result.body(), result.httpStatus(), false);
    }

    private static ApiException inProgress() {
        return new ApiException(org.springframework.http.HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                "A request with this Idempotency-Key is still being processed; retry shortly");
    }

    /**
     * Internal marker: the caller's work threw. Never escapes {@link #run}, which unwraps it
     * so callers still see the original exception.
     */
    private static final class WorkFailed extends RuntimeException {
        WorkFailed(RuntimeException cause) {
            super(cause);
        }

        RuntimeException unwrap() {
            return (RuntimeException) getCause();
        }
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
