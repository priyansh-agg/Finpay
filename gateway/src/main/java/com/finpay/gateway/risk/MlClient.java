package com.finpay.gateway.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Calls the Python ML sidecar's {@code /predict}. The call is on the synchronous risk path,
 * so it has a hard timeout; any failure degrades gracefully to rule-only scoring.
 */
@Component
public class MlClient {

    private static final Logger log = LoggerFactory.getLogger(MlClient.class);

    private final RestClient http;

    public MlClient(@Value("${finpay.risk.ml-base-url}") String baseUrl,
                    @Value("${finpay.risk.ml-timeout-ms:200}") int timeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record MlScore(double probability, String modelVersion) {}

    public Optional<MlScore> predict(RiskFeatures f) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.post().uri("/predict")
                    .body(f.toMap())
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("probability") == null) {
                return Optional.empty();
            }
            double p = ((Number) body.get("probability")).doubleValue();
            String v = String.valueOf(body.getOrDefault("model_version", "unknown"));
            return Optional.of(new MlScore(p, v));
        } catch (Exception e) {
            log.warn("ML /predict unavailable ({}), falling back to rules only", e.toString());
            return Optional.empty();
        }
    }
}
