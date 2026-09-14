package com.finpay.gateway.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A simulation run as a real, queryable record (interview sprint Day 6) — and its exit
 * criterion in one sentence: a run reports exactly the population it was asked for, not the
 * population plus however much scenery the generator needed to produce it.
 *
 * <p>Before this, a "1,000-transaction" run silently created ~1,700 payments — customer-history
 * seeding and pattern warm-ups were indistinguishable, in the payments table, from the
 * population itself, and every metric computed from that table was diluted by transactions
 * nobody asked to have scored.
 */
class SimulationProvenanceTest extends AbstractIntegrationTest {

    @Autowired SimulationService sim;
    @Autowired SimulationRunRepository runs;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper mapper;

    @Test
    void aRun_isRecordedAsAReproducibleRow() throws Exception {
        SimulationScenario scenario = new SimulationScenario(50, 0.2, 4242L);
        SimulationService.SimSummary summary = sim.run(scenario, "test-caller");

        SimulationRun row = runs.findById(summary.runId()).orElseThrow();
        assertThat(row.getSeed()).isEqualTo(4242L);
        assertThat(row.getCreatedBy()).isEqualTo("test-caller");
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getCompletedAt()).isNotNull().isAfterOrEqualTo(row.getStartedAt());
        // The scenario is stored verbatim — the run is reproducible from this row alone.
        // (Checked by value, not by a literal JSON substring: Jackson doesn't promise a
        // stable key order or spacing for a plain record, only the values round-trip.)
        @SuppressWarnings("unchecked")
        Map<String, Object> storedScenario = mapper.readValue(row.getScenario(), Map.class);
        assertThat(storedScenario).containsEntry("count", 50).containsEntry("seed", 4242);
        assertThat(((Number) storedScenario.get("fraudRate")).doubleValue()).isEqualTo(0.2);
        assertThat(row.getSummary()).isNotNull();
    }

    @Test
    void thePopulationReported_matchesWhatIsActuallyInTheDatabase_andExcludesWarmup() {
        SimulationScenario scenario = new SimulationScenario(50, 0.2, 777L);
        SimulationService.SimSummary summary = sim.run(scenario, "test");

        long rawPopulation = count("SELECT count(*) FROM payments WHERE run_id = ? AND is_warmup = false", summary.runId());
        long rawWarmup = count("SELECT count(*) FROM payments WHERE run_id = ? AND is_warmup = true", summary.runId());
        long rawTotal = count("SELECT count(*) FROM payments WHERE run_id = ?", summary.runId());

        assertThat(summary.populationPayments()).as("summary matches independent SQL, not just its own bookkeeping")
                .isEqualTo(rawPopulation);
        assertThat(summary.warmupPayments()).isEqualTo(rawWarmup);
        assertThat(rawPopulation + rawWarmup).isEqualTo(rawTotal);

        // Customer-history seeding is 3 payments per pooled customer, deterministically (no RNG
        // gates it) — a precise lower bound, not just "greater than zero". A mutation check
        // (temporarily tagging the seeding loop's payments as population instead of warmup)
        // confirmed a bare isPositive() here does NOT catch that regression — fraud-pattern
        // warm-ups alone keep it positive — while this bound does.
        int poolSize = Math.max(20, scenario.count() / 12);
        assertThat(rawWarmup).as("customer history seeding (3 per pooled customer) produced warm-up rows")
                .isGreaterThanOrEqualTo(3L * poolSize);
        assertThat(rawPopulation).as("every requested draw produced at least one population payment")
                .isGreaterThanOrEqualTo(scenario.count());

        // Ground truth accounts for every population payment, and only population payments.
        assertThat(summary.legit() + summary.fraud()).isEqualTo(rawPopulation);
        assertThat(summary.bucketCounts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(rawPopulation);
        assertThat(summary.statusCounts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(rawPopulation);
    }

    @Test
    void aFlaggedPayment_carriesItsRunIdOntoTheReviewRow() {
        // fraudRate high enough that at least one review-worthy pattern fires within a small run.
        SimulationScenario scenario = new SimulationScenario(60, 0.35, 55L);
        SimulationService.SimSummary summary = sim.run(scenario, "test");

        long reviewsForThisRun = count("SELECT count(*) FROM analyst_reviews WHERE run_id = ?", summary.runId());
        long reviewsForPaymentsInThisRun = count("""
                SELECT count(*) FROM analyst_reviews r JOIN payments p ON p.id = r.payment_id
                WHERE p.run_id = ?""", summary.runId());
        assertThat(reviewsForThisRun).as("every review queued by this run's payments is tagged with it")
                .isEqualTo(reviewsForPaymentsInThisRun).isPositive();
    }

    @Test
    void realTraffic_hasNoRunAndIsNeverWarmup() {
        UUID merchantId = fixtures.newMerchant();
        var p = fixtures.newCapturedPayment(merchantId, 20_000);
        assertThat(count("SELECT count(*) FROM payments WHERE id = ? AND run_id IS NULL AND is_warmup = false", p.getId()))
                .isEqualTo(1);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
