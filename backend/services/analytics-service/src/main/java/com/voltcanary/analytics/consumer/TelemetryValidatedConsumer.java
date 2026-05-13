package com.voltcanary.analytics.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
public class TelemetryValidatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryValidatedConsumer.class);

    @KafkaListener(topics = "telemetry.validated.v1", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String eventPayload) {
        log.info("Received telemetry.validated.v1 event: {}", eventPayload);
    }
}
