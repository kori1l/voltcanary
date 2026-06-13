package com.voltcanary.analytics.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.domain.AnalyticsEventOutbox;
import com.voltcanary.analytics.repository.AnalyticsEventOutboxRepository;
import com.voltcanary.analytics.domain.RiskScore;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import java.time.Instant;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsOutboxService {

    public static final String ANOMALY_TOPIC = "anomaly.detected.v1";
    public static final String RISK_TOPIC = "risk-score.changed.v1";

    private final AnalyticsEventOutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AnalyticsOutboxService(
            AnalyticsEventOutboxRepository repository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void enqueueAnomaly(Anomaly anomaly) {
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
        enqueue(
                "anomaly:" + anomaly.getDeviceId() + ":" + anomaly.getRuleId() + ":" + anomaly.getWindowStart() + ":" + anomaly.getWindowEnd() + ":" + anomaly.getInputHash(),
                ANOMALY_TOPIC,
                anomaly.getDeviceId().toString(),
                event);
    }

    @Transactional
    public void enqueueRiskScore(RiskScore riskScore, double previousScore) {
        if (Double.compare(previousScore, riskScore.getScore()) == 0) {
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
        enqueue(
                "risk:" + riskScore.getDeviceId() + ":" + riskScore.getWindowStart() + ":" + riskScore.getWindowEnd() + ":" + riskScore.getInputHash(),
                RISK_TOPIC,
                riskScore.getDeviceId().toString(),
                event);
    }

    private void enqueue(String eventKey, String topic, String messageKey, Object payload) {
        if (repository.findByEventKey(eventKey).isPresent()) {
            return;
        }
        AnalyticsEventOutbox outbox = new AnalyticsEventOutbox();
        outbox.setId(UUID.randomUUID());
        outbox.setEventKey(eventKey);
        outbox.setTopic(topic);
        outbox.setMessageKey(messageKey);
        outbox.setPayloadJson(writeJson(payload));
        outbox.setStatus("PENDING");
        outbox.setAttempts(0);
        outbox.setCreatedAt(Instant.now());
        outbox.setNextAttemptAt(outbox.getCreatedAt());
        repository.save(outbox);
        meterRegistry.counter("analytics.events.enqueued", "topic", topic).increment();
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to write analytics outbox payload", exception);
        }
    }
}
