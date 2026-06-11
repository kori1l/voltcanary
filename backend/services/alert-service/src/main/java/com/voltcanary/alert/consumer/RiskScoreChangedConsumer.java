package com.voltcanary.alert.consumer;

import com.voltcanary.alert.service.AnalyticsAlertEventHandler;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
public class RiskScoreChangedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RiskScoreChangedConsumer.class);

    private final AnalyticsAlertEventHandler eventHandler;

    public RiskScoreChangedConsumer(AnalyticsAlertEventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    @KafkaListener(
            topics = "risk-score.changed.v1",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "riskScoreChangedKafkaListenerContainerFactory")
    public void consume(RiskScoreChangedEvent event) {
        boolean processed = eventHandler.handleRiskScoreChanged(event);
        log.info(
                "riskScoreChangedConsumed eventId={} deviceId={} processed={}",
                event.eventId(),
                event.deviceId(),
                processed);
    }
}
