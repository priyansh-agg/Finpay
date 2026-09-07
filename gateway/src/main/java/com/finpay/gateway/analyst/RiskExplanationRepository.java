package com.finpay.gateway.analyst;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RiskExplanationRepository extends JpaRepository<RiskExplanation, UUID> {
    Optional<RiskExplanation> findByPaymentId(UUID paymentId);
}
