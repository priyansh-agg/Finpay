package com.finpay.gateway.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /**
     * SELECT ... FOR UPDATE. Serializes concurrent capture/refund/void on one payment row.
     * This is the lock that makes double-capture / double-refund impossible.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    Page<Payment> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<Payment> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, PaymentState status, Pageable pageable);

    Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Payment> findByStatusOrderByCreatedAtDesc(PaymentState status, Pageable pageable);

    long countByCustomerIdAndCreatedAtAfter(UUID customerId, Instant after);

    long countByCustomerIdAndStatusAndCreatedAtAfter(UUID customerId, PaymentState status, Instant after);
}
