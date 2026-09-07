package com.finpay.gateway.groundtruth;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FraudGroundTruthRepository extends JpaRepository<FraudGroundTruth, UUID> {

    Optional<FraudGroundTruth> findByPaymentId(UUID paymentId);

    @Query("select g from FraudGroundTruth g where g.resolved = false and g.resolveAt <= :now")
    List<FraudGroundTruth> findDueForResolution(@Param("now") Instant now, Pageable pageable);

    long countByResolvedTrueAndActuallyFraudulentTrue();

    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM fraud_ground_truth g
              JOIN payments p ON p.id = g.payment_id
              WHERE p.customer_id = :customerId
                AND g.resolved = true AND g.actually_fraudulent = true
            )
            """, nativeQuery = true)
    boolean customerHasConfirmedFraud(@Param("customerId") UUID customerId);

    @Query("select g from FraudGroundTruth g where g.resolved = true and g.actuallyFraudulent = true")
    List<FraudGroundTruth> allConfirmedFraud();
}
