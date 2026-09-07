package com.finpay.gateway.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {
}
