package com.finpay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FinPay — sandboxed payment gateway with a real-time fraud-detection engine.
 *
 * <p>Deliberately a <b>modular monolith</b>: one deployable Spring Boot service split into
 * modules ({@code auth}, {@code merchant}, {@code order}, {@code payment}, {@code ledger},
 * {@code refund}, {@code risk}, {@code webhook}, {@code analyst}, {@code admin}) that talk
 * only through service interfaces. A 2-person team cannot operate Kafka/K8s and keep
 * correctness; we spent that budget on idempotency and ledger integrity instead.
 */
@SpringBootApplication
@EnableScheduling
public class FinPayApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinPayApplication.class, args);
    }
}
