package com.voltcanary.alert.consumer;

import com.voltcanary.alert.service.AnalyticsAlertEventHandler;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
public class AnomalyDetectedConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectedConsumer.class);

    private final AnalyticsAlertEventHandler eventHandler;

    public AnomalyDetectedConsumer(AnalyticsAlertEventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

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
