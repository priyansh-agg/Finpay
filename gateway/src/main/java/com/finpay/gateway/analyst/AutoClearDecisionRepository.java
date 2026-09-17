package com.finpay.gateway.analyst;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AutoClearDecisionRepository extends JpaRepository<AutoClearDecision, UUID> {

    List<AutoClearDecision> findByPaymentId(UUID paymentId);

    List<AutoClearDecision> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    long countByAutoClearedTrue();

    long countByAutoClearedTrueAndLaterConfirmedFraudTrue();

    long count();

    @Query("select count(d) from AutoClearDecision d where d.autoCleared = false")
    long countNotAutoCleared();
}
