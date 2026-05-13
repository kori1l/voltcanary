package com.voltcanary.analytics.producer;

import com.voltcanary.analytics.anomaly.Anomaly;
import com.voltcanary.analytics.risk.RiskScore;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventPublisher.class);
    private static final String ANOMALY_TOPIC = "anomaly.detected.v1";
    private static final String RISK_TOPIC = "risk-score.changed.v1";

    private final KafkaTemplate<String, AnomalyDetectedEvent> anomalyKafkaTemplate;
    private final KafkaTemplate<String, RiskScoreChangedEvent> riskKafkaTemplate;
    private final boolean publishEnabled;

    public AnalyticsEventPublisher(
            KafkaTemplate<String, AnomalyDetectedEvent> anomalyKafkaTemplate,
            KafkaTemplate<String, RiskScoreChangedEvent> riskKafkaTemplate,
            @Value("${app.kafka.publish-enabled:true}") boolean publishEnabled) {
        this.anomalyKafkaTemplate = anomalyKafkaTemplate;
        this.riskKafkaTemplate = riskKafkaTemplate;
        this.publishEnabled = publishEnabled;
    }

    public void publishAnomaly(Anomaly anomaly) {
        if (!publishEnabled) {
            return;
        }
        AnomalyDetectedEvent event = new AnomalyDetectedEvent(
                anomaly.getId(),
                anomaly.getDeviceId(),
                anomaly.getId(),
                anomaly.getType(),
                anomaly.getSeverity(),
                anomaly.getScore(),
                anomaly.getDescription(),
                anomaly.getDetectedAt());
        log.info("Publishing {} anomalyId={} deviceId={}", ANOMALY_TOPIC, anomaly.getId(), anomaly.getDeviceId());
        anomalyKafkaTemplate.send(ANOMALY_TOPIC, anomaly.getDeviceId().toString(), event);
    }

    public void publishRiskScore(RiskScore riskScore) {
        if (!publishEnabled) {
            return;
        }
        RiskScoreChangedEvent event = new RiskScoreChangedEvent(
                riskScore.getId(),
                riskScore.getDeviceId(),
                0,
                riskScore.getScore(),
                riskScore.getLevel(),
                riskScore.getCalculatedAt());
        log.info("Publishing {} riskScoreId={} deviceId={}", RISK_TOPIC, riskScore.getId(), riskScore.getDeviceId());
        riskKafkaTemplate.send(RISK_TOPIC, riskScore.getDeviceId().toString(), event);
    }
}
