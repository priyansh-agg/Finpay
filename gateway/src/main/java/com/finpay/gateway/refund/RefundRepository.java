package com.finpay.gateway.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdemKey(String idemKey);

    List<Refund> findByPaymentId(UUID paymentId);

    @Query("select coalesce(sum(r.amountMinor), 0) from Refund r where r.paymentId = :paymentId and r.status = com.finpay.gateway.refund.Refund.Status.SUCCEEDED")
    long succeededTotal(@Param("paymentId") UUID paymentId);
}
