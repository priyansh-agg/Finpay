package com.finpay.gateway.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CustomerPaymentStatsRepository extends JpaRepository<CustomerPaymentStats, UUID> {
}
