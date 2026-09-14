package com.finpay.gateway.groundtruth;

import com.finpay.gateway.auth.ApiKeyService;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.sim.SimulationScenario;
import com.finpay.gateway.sim.SimulationService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who is allowed to say a transaction is fraud.
 *
 * <p>The audit's headline finding: the hidden {@code actually_fraudulent} label was planted
 * from {@code "tok_fraud".equals(token)}, and that token was a menu option on the public
 * checkout page. A clean payment could be labelled fraud by anyone, the auto-clear engine
 * would (rightly) clear it, and the reconciler would book a false auto-clear — breaking the
 * project's zero-false-auto-clear claim in one click. The label now belongs to the simulation
 * harness alone.
 */
class GroundTruthLabellingTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired ApiKeyService apiKeys;
    @Autowired SimulationService sim;
    @Autowired GroundTruthResolver resolver;
    @LocalServerPort int port;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void noPublicEndpoint_canAuthorAFraudLabel_evenIfTheRequestTriesTo() {
        UUID merchantId = fixtures.newMerchant();
        String apiKey = apiKeys.issue(merchantId, "MERCHANT", "test").rawKey();
        Order viaApi = fixtures.newOrder(merchantId, 20_000);
        Order viaCheckout = fixtures.newOrder(merchantId, 20_000);

        // Both public doors, each with a label smuggled into the JSON.
        http().post().uri("/v1/payments")
                .header("X-API-Key", apiKey)
                .header("Idempotency-Key", "gt-" + UUID.randomUUID())
                .body(Map.of("orderId", viaApi.getId().toString(), "method", "CARD", "token", "tok_valid",
                        "intendedClass", "FRAUD", "label", Map.of("intendedClass", "FRAUD", "scenarioPattern", "x")))
                .retrieve().toBodilessEntity();
        http().post().uri("/v1/checkout/{id}/pay", viaCheckout.getId())
                .header("Idempotency-Key", "gt-" + UUID.randomUUID())
                .body(Map.of("method", "CARD", "token", "tok_valid",
                        "intendedClass", "FRAUD", "label", Map.of("intendedClass", "FRAUD", "scenarioPattern", "x")))
                .retrieve().toBodilessEntity();

        assertThat(count("SELECT count(*) FROM fraud_ground_truth")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM fraud_ground_truth WHERE intended_class = 'FRAUD'"))
                .as("nothing outside the simulation harness authors a fraud label").isZero();
        assertThat(count("SELECT count(*) FROM fraud_ground_truth WHERE label_source = 'LIVE' AND intended_class = 'LEGIT'"))
                .isEqualTo(2);
    }

    @Test
    void tokFraud_isNoLongerASecretFraudSwitch() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 52_000);

        @SuppressWarnings("unchecked")
        Map<String, Object> payment = http().post().uri("/v1/checkout/{id}/pay", order.getId())
                .header("Idempotency-Key", "gt-" + UUID.randomUUID())
                .body(Map.of("method", "CARD", "token", "tok_fraud"))
                .retrieve().body(Map.class);

        // It is just an unknown card token now: the processor declines it, and the only thing
        // it ever decided — the hidden label — is no longer its to decide.
        assertThat(payment).containsEntry("status", "FAILED").containsEntry("failedReason", "unknown_token");
        assertThat(count("SELECT count(*) FROM fraud_ground_truth WHERE intended_class = 'FRAUD'")).isZero();
    }

    @Test
    void theSimulationHarness_authorsTheLabels_andResolutionFollowsThem() {
        sim.run(new SimulationScenario(40, 0.3, 0.0, 0.0, 7L), "test");

        assertThat(count("SELECT count(*) FROM fraud_ground_truth WHERE intended_class = 'FRAUD'")).isPositive();
        assertThat(count("SELECT count(*) FROM fraud_ground_truth WHERE intended_class = 'LEGIT'")).isPositive();
        assertThat(count("SELECT count(*) FROM fraud_ground_truth WHERE label_source <> 'SIMULATION'")).isZero();

        // Every fraud label names its real pattern — and fraud_type, which the pgvector store
        // and the analyst's explanation read, now carries it instead of a hardcoded constant.
        List<String> fraudPatterns = jdbc.queryForList(
                "SELECT DISTINCT scenario_pattern FROM fraud_ground_truth WHERE intended_class = 'FRAUD'", String.class);
        assertThat(fraudPatterns)
                .isSubsetOf("velocity_abuse", "new_device_high_value", "amount_deviation", "failed_attempt_burst")
                .hasSizeGreaterThan(1);
        assertThat(count("SELECT count(*) FROM fraud_ground_truth WHERE intended_class = 'FRAUD' "
                + "AND fraud_type IS DISTINCT FROM scenario_pattern")).isZero();

        // Close the dispute window now rather than waiting 60s, then resolve.
        jdbc.update("UPDATE fraud_ground_truth SET resolve_at = now() - interval '1 second'");
        for (int i = 0; i < 20 && count("SELECT count(*) FROM fraud_ground_truth WHERE NOT resolved") > 0; i++) {
            resolver.resolveDue();
        }
        assertThat(count("SELECT count(*) FROM fraud_ground_truth WHERE NOT resolved")).isZero();
        assertThat(count("SELECT count(*) FROM fraud_ground_truth "
                + "WHERE actually_fraudulent IS DISTINCT FROM (intended_class = 'FRAUD')"))
                .as("resolution reports exactly what the author said").isZero();

        // Confirmed fraud is embedded under its real pattern, not all as "velocity_abuse".
        assertThat(count("SELECT count(*) FROM fraud_case_vectors"))
                .isEqualTo(count("SELECT count(*) FROM fraud_ground_truth WHERE intended_class = 'FRAUD'"));
        assertThat(jdbc.queryForList("SELECT DISTINCT fraud_type FROM fraud_case_vectors", String.class))
                .hasSizeGreaterThan(1);
    }

    @Test
    void aLabelMustBeComplete() {
        assertThatThrownBy(() -> GroundTruthLabel.fraud("velocity_abuse", 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GroundTruthLabel.fraud(" ", 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GroundTruthLabel(null, "regular", 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
