package com.finpay.gateway.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for tests that need the FULL Phase 2/3 stack (real RiskEngine + review queue +
 * auto-clear), i.e. NOT the {@code phase1} profile. The ML sidecar is not required — the
 * ML/auto-clear clients degrade to rule-only / no-auto-clear when it is unreachable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractFraudStackTest {

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected Flyway flyway;

    @BeforeEach
    void wipeData() {
        jdbc.execute("""
            TRUNCATE TABLE
              webhook_deliveries, webhook_endpoints, outbox_events,
              settlements, settlement_batches,
              risk_explanations, autoclear_decisions, analyst_reviews, fraud_case_vectors,
              risk_evaluations, fraud_ground_truth, customer_payment_stats, system_config,
              ledger_entries, ledger_transactions, ledger_accounts,
              refunds, payment_attempts, payments, orders,
              idempotency_keys, devices, customers, api_keys, users, merchants, audit_logs
            RESTART IDENTITY CASCADE
            """);
        jdbc.execute("""
            INSERT INTO ledger_accounts (account_type, owner_type, owner_id, currency) VALUES
              ('CUSTOMER_CLEARING','PLATFORM',NULL,'INR'), ('PLATFORM_FEES','PLATFORM',NULL,'INR')
            """);
        jdbc.execute("INSERT INTO system_config(key,value) VALUES ('autoclear.threshold','0.50')");
    }
}
