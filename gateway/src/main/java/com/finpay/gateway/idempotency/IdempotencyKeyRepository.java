package com.finpay.gateway.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByMerchantIdAndIdemKey(UUID merchantId, String idemKey);

    /**
     * The concurrency primitive. Returns the number of rows inserted:
     *   1  -> this caller now OWNS the request and must process it
     *   0  -> a concurrent caller won the race; read their row and mirror the outcome
     *
     * <p>The conflict target is {@code (merchant_id, idem_key)}: key strings are chosen by
     * the caller, so they are only unique within one merchant (V10).
     *
     * <p>Runs in the CALLER's transaction on purpose. Holding the uncommitted insert for
     * the duration of the work is what makes a concurrent caller block on the unique index
     * and then read the winner's cached response, instead of being told to retry.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO idempotency_keys (id, idem_key, merchant_id, endpoint, request_hash, status, expires_at)
            VALUES (gen_random_uuid(), :key, :merchantId, :endpoint, :hash, 'PROCESSING', :expiresAt)
            ON CONFLICT (merchant_id, idem_key) DO NOTHING
            """, nativeQuery = true)
    int tryClaim(@Param("key") String key,
                 @Param("merchantId") UUID merchantId,
                 @Param("endpoint") String endpoint,
                 @Param("hash") String hash,
                 @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE idempotency_keys
               SET status = :status, response_status = :respStatus, response_body = CAST(:respBody AS jsonb)
             WHERE merchant_id = :merchantId AND idem_key = :key
            """, nativeQuery = true)
    void complete(@Param("merchantId") UUID merchantId,
                  @Param("key") String key,
                  @Param("status") String status,
                  @Param("respStatus") int respStatus,
                  @Param("respBody") String respBody);

    /**
     * Takes over a key that is free to be used again — its last attempt FAILED, or its TTL
     * has passed — flipping it to a fresh PROCESSING claim. Returns 1 when this caller won
     * the takeover, 0 when someone else got there first.
     *
     * <p>In the caller's transaction, like a fresh claim: the row stays locked for the whole
     * retried work. A competing caller's UPDATE blocks on that row lock and, once the winner
     * commits, re-evaluates the WHERE clause and matches nothing.
     *
     * <p>{@code clearAutomatically} matters: the caller re-reads the row after losing a
     * takeover, and without clearing the persistence context Hibernate would hand back the
     * stale entity it loaded before the UPDATE rather than the winner's committed row.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE idempotency_keys
               SET status = 'PROCESSING', endpoint = :endpoint, request_hash = :hash,
                   expires_at = :expiresAt, created_at = now(),
                   response_status = NULL, response_body = NULL
             WHERE merchant_id = :merchantId AND idem_key = :key
               AND (status = 'FAILED' OR expires_at < now())
            """, nativeQuery = true)
    int reclaim(@Param("merchantId") UUID merchantId,
                @Param("key") String key,
                @Param("endpoint") String endpoint,
                @Param("hash") String hash,
                @Param("expiresAt") Instant expiresAt);

    /**
     * Records that this key's attempt failed — in its OWN transaction, and only ever called
     * AFTER the failed attempt's transaction has already rolled back.
     *
     * <p>Both halves of that sentence are load-bearing:
     * <ul>
     *   <li>Own transaction, because the claim row was inserted by the transaction that just
     *       rolled back — the marker has to outlive it, or the failure leaves no trace.</li>
     *   <li>After the rollback, never during it. Calling this from inside the failing
     *       transaction would have the new transaction wait on the old one's uncommitted
     *       insert of the same key, while the old one waits for this call to return:
     *       a self-deadlock that only shows up under a real failure.</li>
     * </ul>
     *
     * <p>{@code DO NOTHING} on conflict: if another caller has claimed the key in the gap,
     * it owns it now and this stale marker must not disturb it.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            INSERT INTO idempotency_keys (id, idem_key, merchant_id, endpoint, request_hash, status, expires_at)
            VALUES (gen_random_uuid(), :key, :merchantId, :endpoint, :hash, 'FAILED', :expiresAt)
            ON CONFLICT (merchant_id, idem_key) DO NOTHING
            """, nativeQuery = true)
    void recordFailure(@Param("key") String key,
                       @Param("merchantId") UUID merchantId,
                       @Param("endpoint") String endpoint,
                       @Param("hash") String hash,
                       @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Transactional
    @Query("delete from IdempotencyKey k where k.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
