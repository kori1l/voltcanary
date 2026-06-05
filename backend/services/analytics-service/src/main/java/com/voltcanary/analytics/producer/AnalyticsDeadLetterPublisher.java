package com.voltcanary.analytics.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.config.AnalyticsDlqProperties;
import com.voltcanary.analytics.config.AnalyticsKafkaProperties;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsDeadLetterPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final boolean publishEnabled;

    public AnalyticsDeadLetterPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            AnalyticsDlqProperties dlqProperties,
            AnalyticsKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = dlqProperties.getTopic();
        this.publishEnabled = kafkaProperties.isPublishEnabled();
    }

    public void publish(String sourceTopic, String originalPayload, String reason) {
        if (!publishEnabled) {
            return;
        }
        String deadLetterId = UUID.randomUUID().toString();
        kafkaTemplate.send(topic, deadLetterId, writeJson(Map.of(
                "deadLetterId", deadLetterId,
                "sourceTopic", sourceTopic,
                "reason", reason,
                "originalPayload", originalPayload == null ? "" : originalPayload,
                "failedAt", Instant.now().toString())));
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to write analytics DLQ payload", exception);
        }
    }
}
