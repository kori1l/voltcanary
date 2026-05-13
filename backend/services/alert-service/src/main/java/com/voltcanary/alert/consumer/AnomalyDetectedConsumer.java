package com.voltcanary.alert.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
public class AnomalyDetectedConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectedConsumer.class);

    @KafkaListener(topics = "anomaly.detected.v1", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String eventPayload) {
        log.info("Received anomaly.detected.v1 event: {}", eventPayload);
    }
}
