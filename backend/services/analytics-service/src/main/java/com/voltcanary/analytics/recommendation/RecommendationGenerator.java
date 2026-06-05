package com.voltcanary.analytics.recommendation;

import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.domain.Recommendation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RecommendationGenerator {

    public List<Recommendation> generate(UUID deviceId, List<Anomaly> anomalies, Instant now) {
        List<Recommendation> recommendations = new ArrayList<>();
        for (Anomaly anomaly : anomalies) {
            if ("CONSUMPTION_GROWTH".equals(anomaly.getType())) {
                recommendations.add(recommendation(
                        deviceId,
                        "Check consumption growth",
                        "Average consumption increased compared to baseline. Check operating mode and external conditions. If the anomaly repeats, maintenance is recommended.",
                        "MEDIUM",
                        now));
            }
            if ("POWER_SPIKE".equals(anomaly.getType())) {
                recommendations.add(recommendation(
                        deviceId,
                        "Check power conditions",
                        "A power spike was detected above the normal range. Check device load and power conditions.",
                        "MEDIUM",
                        now));
            }
        }
        return recommendations;
    }

    private Recommendation recommendation(UUID deviceId, String title, String description, String priority, Instant now) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setDeviceId(deviceId);
        recommendation.setTitle(title);
        recommendation.setDescription(description);
        recommendation.setPriority(priority);
        recommendation.setStatus("ACTIVE");
        recommendation.setCreatedAt(now);
        return recommendation;
    }
}
