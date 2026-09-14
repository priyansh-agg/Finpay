package com.finpay.gateway.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdAndMerchantId(UUID id, UUID merchantId);

    /**
     * SELECT ... FOR UPDATE on the order. Creating a payment is check-then-insert ("is this
     * order still payable, and does it already have a live payment?"); without the row lock
     * two requests can both pass the check before either inserts. Holding the lock until the
     * payment transaction commits turns that race into a queue.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.merchantId = :merchantId")
    Optional<Order> findByIdAndMerchantIdForUpdate(@Param("id") UUID id, @Param("merchantId") UUID merchantId);
}
