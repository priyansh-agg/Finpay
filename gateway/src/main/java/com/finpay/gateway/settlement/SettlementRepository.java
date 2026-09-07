package com.finpay.gateway.settlement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
    Page<Settlement> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);
}
