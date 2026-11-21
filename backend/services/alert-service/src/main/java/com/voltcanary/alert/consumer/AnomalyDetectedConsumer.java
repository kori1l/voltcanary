package com.voltcanary.alert.consumer;

import com.voltcanary.alert.service.AnalyticsAlertEventHandler;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AnomalyDetectedConsumer {

    private final AnalyticsAlertEventHandler eventHandler;

    @KafkaListener(
            topics = "anomaly.detected.v1",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "anomalyDetectedKafkaListenerContainerFactory")
    public void consume(AnomalyDetectedEvent event) {
        boolean processed = eventHandler.handleAnomaly(event);
        log.info(
                "anomalyDetectedConsumed eventId={} deviceId={} processed={}",
                event.eventId(),
                event.deviceId(),
                processed);
    }
}
