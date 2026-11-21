package com.voltcanary.alert.consumer;

import com.voltcanary.alert.service.AnalyticsAlertEventHandler;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RiskScoreChangedConsumer {

    private final AnalyticsAlertEventHandler eventHandler;

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
