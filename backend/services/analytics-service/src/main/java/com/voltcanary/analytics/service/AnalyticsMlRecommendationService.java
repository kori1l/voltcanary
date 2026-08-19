package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.PredictionResult;
import com.voltcanary.analytics.domain.Recommendation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsMlRecommendationService {

    private final AnalyticsPredictionTraceService traceService;

    public AnalyticsMlRecommendationService(AnalyticsPredictionTraceService traceService) {
        this.traceService = traceService;
    }

    public List<Recommendation> mlRecommendations(UUID deviceId, PredictionResult prediction, Instant now) {
        if (prediction == null || prediction.getPredictionJson() == null) {
            return List.of();
        }
        Map<String, Object> values = traceService.readJson(prediction.getPredictionJson());
        String riskLevel = prediction.getRiskLevel() == null ? string(values.get("riskLevel")) : prediction.getRiskLevel();
        if (!List.of("MEDIUM", "HIGH", "CRITICAL").contains(riskLevel)) {
            return List.of();
        }
        Object actions = values.get("recommendedActions");
        if (!(actions instanceof List<?> actionList) || actionList.isEmpty()) {
            return List.of();
        }
        Object signals = values.get("signals");
        List<String> signalDescriptions = signals instanceof List<?> signalList
                ? signalList.stream()
                        .map(Object::toString)
                        .limit(3)
                        .toList()
                : List.of();
        return actionList.stream()
                .map(Object::toString)
                .filter(action -> !action.isBlank())
                .map(action -> mlRecommendation(deviceId, action, signalDescriptions, riskLevel, now))
                .toList();
    }

    private Recommendation mlRecommendation(
            UUID deviceId,
            String action,
            List<String> signalDescriptions,
            String riskLevel,
            Instant now) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setDeviceId(deviceId);
        recommendation.setTitle("Review ML prediction signal");
        recommendation.setDescription(signalDescriptions.isEmpty()
                ? action
                : action + " Signals: " + String.join("; ", signalDescriptions));
        recommendation.setPriority("CRITICAL".equals(riskLevel) || "HIGH".equals(riskLevel) ? "HIGH" : "MEDIUM");
        recommendation.setStatus("ACTIVE");
        recommendation.setCreatedAt(now);
        return recommendation;
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
