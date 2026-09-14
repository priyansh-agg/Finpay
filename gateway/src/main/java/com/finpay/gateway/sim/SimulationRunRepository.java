package com.finpay.gateway.sim;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, UUID> {
    Page<SimulationRun> findByMerchantIdOrderByStartedAtDesc(UUID merchantId, Pageable pageable);
}
