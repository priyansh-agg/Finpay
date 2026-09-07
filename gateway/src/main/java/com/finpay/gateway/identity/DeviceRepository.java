package com.finpay.gateway.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    Optional<Device> findByCustomerIdAndFingerprint(UUID customerId, String fingerprint);

    long countByCustomerId(UUID customerId);
}
