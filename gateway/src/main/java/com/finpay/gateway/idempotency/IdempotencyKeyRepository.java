package com.finpay.gateway.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByIdemKey(String idemKey);

    /**
     * The concurrency primitive. Returns the number of rows inserted:
     *   1  -> this caller now OWNS the request and must process it
     *   0  -> a concurrent caller won the race; read their row and mirror the outcome
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO idempotency_keys (id, idem_key, merchant_id, endpoint, request_hash, status, expires_at)
            VALUES (gen_random_uuid(), :key, :merchantId, :endpoint, :hash, 'PROCESSING', :expiresAt)
            ON CONFLICT (idem_key) DO NOTHING
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
             WHERE idem_key = :key
            """, nativeQuery = true)
    void complete(@Param("key") String key,
                  @Param("status") String status,
                  @Param("respStatus") int respStatus,
                  @Param("respBody") String respBody);

    @Modifying
    @Transactional
    @Query("delete from IdempotencyKey k where k.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
