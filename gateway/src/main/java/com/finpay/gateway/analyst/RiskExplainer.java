package com.finpay.gateway.analyst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.risk.FraudVectorStore;
import com.finpay.gateway.risk.RiskEvaluation;
import com.finpay.gateway.risk.RiskEvaluationRepository;
import com.finpay.gateway.risk.RiskFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Explain this flag" for the risk-analyst dashboard. Combines the rule flags, the scores,
 * and the pgvector-retrieved similar fraud cases into a short natural-language explanation
 * via Spring AI + a local Ollama model. Degrades to a deterministic template if the LLM is
 * unavailable. Explanations are cached per payment.
 */
@Service
public class RiskExplainer {

    private static final Logger log = LoggerFactory.getLogger(RiskExplainer.class);

    private final RiskExplanationRepository cache;
    private final RiskEvaluationRepository evaluations;
    private final FraudVectorStore vectors;
    private final ObjectMapper json;
    private final ChatClient chatClient;

    public RiskExplainer(RiskExplanationRepository cache, RiskEvaluationRepository evaluations,
                         FraudVectorStore vectors, ObjectMapper json, ObjectProvider<ChatModel> chatModel) {
        this.cache = cache;
        this.evaluations = evaluations;
        this.vectors = vectors;
        this.json = json;
        ChatModel model = chatModel.getIfAvailable();
        this.chatClient = model == null ? null : ChatClient.builder(model).build();
    }

    public record Explanation(String text, String model, List<String> similarCases) {}

    @Transactional
    public Explanation explain(UUID paymentId, boolean refresh) {
        if (!refresh) {
            var cached = cache.findByPaymentId(paymentId);
            if (cached.isPresent()) {
                return new Explanation(cached.get().getExplanation(), cached.get().getModel(),
                        parseList(cached.get().getSimilarCases()));
            }
        }
        RiskEvaluation eval = evaluations.findFirstByPaymentIdOrderByCreatedAtDesc(paymentId).orElse(null);
        if (eval == null) {
            return new Explanation("No risk evaluation exists for this payment.", "none", List.of());
        }

        List<FraudVectorStore.SimilarCase> similar = List.of();
        RiskFeatures features = parseFeatures(eval);
        if (features != null) {
            similar = vectors.topSimilar(vectors.encode(features));
        }
        List<String> similarSummary = similar.stream()
                .map(s -> String.format("%.0f%% similar to a %s case", s.similarity() * 100, s.fraudType()))
                .collect(Collectors.toList());

        String facts = buildFacts(eval, similarSummary);
        String text;
        String modelName;
        if (chatClient != null) {
            try {
                text = chatClient.prompt()
                        .system("You are a payments fraud analyst assistant. In 2-3 sentences, plain English, "
                                + "explain why this transaction was flagged and whether the similar past cases "
                                + "strengthen the concern. Do not invent facts beyond those given.")
                        .user(facts)
                        .call()
                        .content();
                modelName = "ollama";
            } catch (Exception e) {
                log.warn("LLM explain failed ({}), using template", e.toString());
                text = templateExplanation(eval, similarSummary);
                modelName = "template-fallback";
            }
        } else {
            text = templateExplanation(eval, similarSummary);
            modelName = "template";
        }

        RiskExplanation row = cache.findByPaymentId(paymentId).orElseGet(RiskExplanation::new);
        row.setPaymentId(paymentId);
        row.setExplanation(text);
        row.setModel(modelName);
        row.setSimilarCases(writeJson(similarSummary));
        cache.save(row);
        return new Explanation(text, modelName, similarSummary);
    }

    private String buildFacts(RiskEvaluation e, List<String> similar) {
        StringBuilder sb = new StringBuilder();
        sb.append("final_score=").append(e.getFinalScore()).append("/100, bucket=").append(e.getBucket())
                .append(", decision=").append(e.getDecision()).append(".\n");
        sb.append("rule_score=").append(e.getRuleScore());
        if (e.getMlScore() != null) sb.append(", ml_score=").append(e.getMlScore());
        if (e.getVectorSimilarity() != null) sb.append(", avg_similarity_to_known_fraud=").append(e.getVectorSimilarity());
        sb.append(".\nrules_fired: ").append(ruleCodes(e)).append(".\n");
        sb.append("features: ").append(e.getFeatures()).append(".\n");
        if (!similar.isEmpty()) sb.append("similar_past_fraud: ").append(String.join("; ", similar)).append(".");
        return sb.toString();
    }

    private String templateExplanation(RiskEvaluation e, List<String> similar) {
        String rules = ruleCodes(e);
        String base = String.format(
                "Flagged with a risk score of %s/100 (%s). %s",
                e.getFinalScore(), e.getBucket(),
                rules.isBlank() ? "No individual rules fired; the score is driven by the model."
                        : "Contributing rules: " + rules + ".");
        if (!similar.isEmpty()) {
            base += " This transaction is " + String.join(", ", similar)
                    + ", which reinforces the concern.";
        }
        return base;
    }

    private String ruleCodes(RiskEvaluation e) {
        try {
            JsonNode arr = json.readTree(e.getRuleFlags());
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : arr) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(n.path("code").asText()).append(" (").append(n.path("detail").asText()).append(")");
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private RiskFeatures parseFeatures(RiskEvaluation e) {
        try { return json.readValue(e.getFeatures(), RiskFeatures.class); }
        catch (Exception ex) { return null; }
    }

    private List<String> parseList(String s) {
        try { return json.readValue(s, json.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception e) { return List.of(); }
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }
}
