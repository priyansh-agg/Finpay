package com.finpay.gateway.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RiskEvaluationRepository extends JpaRepository<RiskEvaluation, UUID> {
    Optional<RiskEvaluation> findFirstByPaymentIdOrderByCreatedAtDesc(UUID paymentId);
}
