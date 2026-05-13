package com.voltcanary.alert.consumer;

import com.voltcanary.alert.service.AlertService;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
public class RiskScoreChangedConsumer {

    private final AlertService alertService;

    public RiskScoreChangedConsumer(AlertService alertService) {
        this.alertService = alertService;
    }

    @KafkaListener(
            topics = "risk-score.changed.v1",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "riskScoreChangedKafkaListenerContainerFactory")
    public void consume(RiskScoreChangedEvent event) {
        alertService.handleRiskScoreChanged(event);
    }
}
