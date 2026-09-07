package com.finpay.gateway.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    @Modifying
    @Query("update ApiKey k set k.lastUsedAt = :ts where k.id = :id")
    void touch(@Param("id") UUID id, @Param("ts") Instant ts);
}
