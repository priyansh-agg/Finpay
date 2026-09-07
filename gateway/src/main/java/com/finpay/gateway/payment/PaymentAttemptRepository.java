package com.finpay.gateway.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
    List<PaymentAttempt> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
