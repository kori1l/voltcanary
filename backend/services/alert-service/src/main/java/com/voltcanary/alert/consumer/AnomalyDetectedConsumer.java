package com.voltcanary.alert.consumer;

import com.voltcanary.alert.service.AlertService;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
public class AnomalyDetectedConsumer {

    private final AlertService alertService;

    public AnomalyDetectedConsumer(AlertService alertService) {
        this.alertService = alertService;
    }

    @KafkaListener(
            topics = "anomaly.detected.v1",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "anomalyDetectedKafkaListenerContainerFactory")
    public void consume(AnomalyDetectedEvent event) {
        alertService.handleAnomaly(event);
    }
}
