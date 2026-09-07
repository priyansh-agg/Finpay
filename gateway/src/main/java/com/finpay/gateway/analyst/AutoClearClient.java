package com.finpay.gateway.analyst;

import com.finpay.gateway.risk.RiskFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calls the ML sidecar's Auto-Clear model: "given this flagged transaction, what is the
 * probability the reference analyst would CLEAR it?" Trained on the reference analyst's
 * historical decisions (silver labels) — NOT on the fraud ground truth.
 */
@Component
public class AutoClearClient {

    private static final Logger log = LoggerFactory.getLogger(AutoClearClient.class);

    private final RestClient http;

    public AutoClearClient(@Value("${finpay.risk.ml-base-url}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record Prediction(double clearConfidence, String modelVersion, List<TopFeature> topFeatures) {}

    public record TopFeature(String feature, double contribution) {}

    public Optional<Prediction> predictClearProbability(RiskFeatures f, double riskScore) {
        try {
            var payload = new java.util.HashMap<>(f.toMap());
            payload.put("risk_score", riskScore);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.post().uri("/autoclear/predict")
                    .body(payload).retrieve().body(Map.class);
            if (body == null || body.get("clear_confidence") == null) return Optional.empty();
            double conf = ((Number) body.get("clear_confidence")).doubleValue();
            String version = String.valueOf(body.getOrDefault("model_version", "unknown"));
            List<TopFeature> top = List.of();
            if (body.get("top_features") instanceof List<?> raw) {
                top = raw.stream().map(o -> {
                    Map<?, ?> m = (Map<?, ?>) o;
                    return new TopFeature(String.valueOf(m.get("feature")),
                            ((Number) m.get("contribution")).doubleValue());
                }).toList();
            }
            return Optional.of(new Prediction(conf, version, top));
        } catch (Exception e) {
            log.warn("auto-clear /predict unavailable ({}) — will route to reference analyst", e.toString());
            return Optional.empty();
        }
    }
}
