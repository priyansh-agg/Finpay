package com.finpay.gateway.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for DB-backed tests: the real application against the local {@code finpay_test}
 * database.
 *
 * <p>Runs the FULL risk stack — {@code RiskEngine} (rules + ML client + pgvector), the review
 * queue and the auto-clear decider — under production risk configuration. It used to run
 * under the {@code phase1} profile, where {@code AllowAllRiskGate} scores every payment
 * LOW / 0, so the idempotency, refund, ledger, webhook and checkout suites had never once seen
 * a real risk decision. (A separate suite had its own base class with its own, already
 * diverging, list of tables to wipe; this is now the only one.)
 *
 * <p>Deterministic by construction:
 * <ul>
 *   <li>the ML sidecar URL points at a closed port (application-test.yml), so scores come
 *       from the rule engine alone whatever happens to be running on :9000;</li>
 *   <li>every table is truncated and re-seeded before each test;</li>
 *   <li>the Redis feature store is wiped too — see {@link #wipeFeatureStore()}.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected Flyway flyway;

    @Autowired
    protected StringRedisTemplate redis;

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
              idempotency_keys, devices, customers, api_keys, users, merchants,
              audit_logs
            RESTART IDENTITY CASCADE
            """);
        // Re-seed what the migrations seed (V5 platform accounts, V8 auto-clear threshold).
        jdbc.execute("""
            INSERT INTO ledger_accounts (account_type, owner_type, owner_id, currency) VALUES
              ('CUSTOMER_CLEARING','PLATFORM',NULL,'INR'),
              ('PLATFORM_FEES','PLATFORM',NULL,'INR'),
              ('CUSTOMER_CLEARING','PLATFORM',NULL,'USD'),
              ('PLATFORM_FEES','PLATFORM',NULL,'USD')
            """);
        jdbc.execute("INSERT INTO system_config(key, value) VALUES ('autoclear.threshold', '0.50')");
        wipeFeatureStore();
    }

    /**
     * Postgres is not the only place state hides. The Redis feature store holds velocity
     * counters, seen-IP sets and device&rarr;account sets on 30-day TTLs, none of which a
     * TRUNCATE touches. Without this, a test that uses a fixed device fingerprint registers
     * one more customer against it on every run, until {@code distinctAccountsForDevice}
     * crosses 3, DEVICE_MANY_ACCOUNTS fires (+30) and the payment lands in a different risk
     * bucket entirely — passing on a clean machine and failing on the sixth run.
     */
    protected void wipeFeatureStore() {
        var keys = redis.keys("finpay:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
