package com.voltcanary.telemetry.consumer;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.service.TelemetryEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.kafka.consume-enabled", havingValue = "true")
public class TelemetryRawConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryRawConsumer.class);

    private final TelemetryEventProcessor telemetryEventProcessor;

    public TelemetryRawConsumer(TelemetryEventProcessor telemetryEventProcessor) {
        this.telemetryEventProcessor = telemetryEventProcessor;
    }

    @KafkaListener(topics = "telemetry.raw.v1", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(TelemetryReceivedEvent event) {
        log.info(
                "Consumed raw telemetry eventId={} deviceId={} metricCount={}",
                event.eventId(),
                event.deviceId(),
                event.metrics().size());
        telemetryEventProcessor.process(event);
    }
}
