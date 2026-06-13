package com.voltcanary.analytics.producer;

import com.voltcanary.analytics.config.AnalyticsKafkaProperties;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.domain.RiskScore;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            AnalyticsKafkaProperties kafkaProperties) {
        this.anomalyKafkaTemplate = anomalyKafkaTemplate;
        this.riskKafkaTemplate = riskKafkaTemplate;
        this.publishEnabled = kafkaProperties.isPublishEnabled();
    }

    public void publishAnomaly(Anomaly anomaly) {
        if (!publishEnabled) {
            return;
        }
        AnomalyDetectedEvent event = new AnomalyDetectedEvent(
                anomaly.getId(),
                anomaly.getDeviceId(),
                anomaly.getId(),
                anomaly.getRuleId(),
                anomaly.getWindowStart(),
                anomaly.getWindowEnd(),
                anomaly.getInputHash(),
                anomaly.getType(),
                anomaly.getSeverity(),
                anomaly.getScore(),
                anomaly.getDescription(),
                anomaly.getDetectedAt(),
                "v1",
                null,
                "analytics-service",
                null,
                anomaly.getDetectedAt());
        log.info("Publishing {} anomalyId={} deviceId={}", ANOMALY_TOPIC, anomaly.getId(), anomaly.getDeviceId());
        anomalyKafkaTemplate.send(ANOMALY_TOPIC, anomaly.getDeviceId().toString(), event);
    }

    public void publishRiskScore(RiskScore riskScore, double previousScore) {
        if (!publishEnabled) {
            return;
        }
        if (Double.compare(previousScore, riskScore.getScore()) == 0) {
            log.info("Skipping {} riskScoreId={} deviceId={} because score did not change",
                    RISK_TOPIC,
                    riskScore.getId(),
                    riskScore.getDeviceId());
            return;
        }
        RiskScoreChangedEvent event = new RiskScoreChangedEvent(
                riskScore.getId(),
                riskScore.getDeviceId(),
                riskScore.getId(),
                riskScore.getWindowStart(),
                riskScore.getWindowEnd(),
                riskScore.getInputHash(),
                previousScore,
                riskScore.getScore(),
                riskScore.getLevel(),
                riskScore.getCalculatedAt(),
                "v1",
                null,
                "analytics-service",
                null,
                riskScore.getCalculatedAt());
        log.info("Publishing {} riskScoreId={} deviceId={}", RISK_TOPIC, riskScore.getId(), riskScore.getDeviceId());
        riskKafkaTemplate.send(RISK_TOPIC, riskScore.getDeviceId().toString(), event);
    }
}
