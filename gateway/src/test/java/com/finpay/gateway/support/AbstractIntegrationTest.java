package com.finpay.gateway.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for DB-backed tests. Uses the local {@code finpay_test} database and truncates
 * all data (keeping the schema + seed rows) before each test so counts are deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "phase1"})
public abstract class AbstractIntegrationTest {

    private static boolean migrated = false;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected Flyway flyway;

    @BeforeAll
    static void ensureMigrated() {
        // Flyway auto-migrates on context start; nothing extra needed here.
        migrated = true;
    }

    @BeforeEach
    void wipeData() {
        jdbc.execute("""
            TRUNCATE TABLE
              webhook_deliveries, webhook_endpoints, outbox_events,
              settlements, settlement_batches,
              risk_explanations, autoclear_decisions, analyst_reviews, fraud_case_vectors,
              risk_evaluations, fraud_ground_truth,
              ledger_entries, ledger_transactions, ledger_accounts,
              refunds, payment_attempts, payments, orders,
              idempotency_keys, devices, customers, api_keys, users, merchants,
              audit_logs
            RESTART IDENTITY CASCADE
            """);
        // Re-seed platform ledger accounts (V5) that the TRUNCATE above removed.
        jdbc.execute("""
            INSERT INTO ledger_accounts (account_type, owner_type, owner_id, currency) VALUES
              ('CUSTOMER_CLEARING','PLATFORM',NULL,'INR'),
              ('PLATFORM_FEES','PLATFORM',NULL,'INR'),
              ('CUSTOMER_CLEARING','PLATFORM',NULL,'USD'),
              ('PLATFORM_FEES','PLATFORM',NULL,'USD')
            """);
    }
}
