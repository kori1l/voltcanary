package com.voltcanary.analytics.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
            @Value("${app.analytics.dlq.topic:analytics.telemetry.validated.dlq.v1}") String topic,
            @Value("${app.kafka.publish-enabled:true}") boolean publishEnabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.publishEnabled = publishEnabled;
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
