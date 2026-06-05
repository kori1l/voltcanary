package com.voltcanary.analytics.risk;

import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.domain.RiskScore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class RiskScoreCalculator {

    private final ObjectMapper objectMapper;

    public RiskScoreCalculator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RiskScore calculate(
            UUID deviceId,
            List<Anomaly> anomalies,
            int sampleCount,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            Instant now) {
        int score = 0;
        List<Map<String, Object>> factors = new ArrayList<>();
        for (Anomaly anomaly : anomalies) {
            int weight = switch (anomaly.getType()) {
                case "POWER_SPIKE", "CONSUMPTION_GROWTH" -> 30;
                case "LONG_CYCLE" -> 20;
                default -> 0;
            };
            score += weight;
            factors.add(Map.of(
                    "code", "ANOMALY_" + anomaly.getType(),
                    "weight", weight,
                    "severity", anomaly.getSeverity(),
                    "ruleId", anomaly.getRuleId() == null ? anomaly.getType() : anomaly.getRuleId()));
        }
        if (sampleCount < 3) {
            score += 10;
            factors.add(Map.of("code", "LOW_SAMPLE_SIZE", "weight", 10, "sampleCount", sampleCount));
        }
        if (sampleCount == 0) {
            score += 10;
            factors.add(Map.of("code", "NO_TELEMETRY", "weight", 10));
        }
        score = Math.min(100, score);

        RiskScore riskScore = new RiskScore();
        riskScore.setId(UUID.nameUUIDFromBytes((deviceId + ":" + windowStart + ":" + windowEnd + ":" + inputHash)
                .getBytes(StandardCharsets.UTF_8)));
        riskScore.setDeviceId(deviceId);
        riskScore.setScore(score);
        riskScore.setLevel(level(score));
        riskScore.setExplanationJson(writeJson(Map.of(
                "modelVersion", "risk-basic-v2",
                "sampleCount", sampleCount,
                "factors", factors,
                "cap", 100)));
        riskScore.setWindowStart(windowStart);
        riskScore.setWindowEnd(windowEnd);
        riskScore.setInputHash(inputHash);
        riskScore.setCalculatedAt(now);
        return riskScore;
    }

    private String level(int score) {
        if (score <= 30) {
            return "LOW";
        }
        if (score <= 60) {
            return "MEDIUM";
        }
        if (score <= 80) {
            return "HIGH";
        }
        return "CRITICAL";
    }

    private String writeJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to write risk explanation JSON", exception);
        }
    }
}
