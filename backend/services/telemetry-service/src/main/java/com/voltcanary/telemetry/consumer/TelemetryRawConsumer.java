package com.voltcanary.telemetry.consumer;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.variable.ConfiguredTelemetryHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.kafka.consume-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class TelemetryRawConsumer {

    private final ConfiguredTelemetryHandler telemetryEventProcessor;

    @KafkaListener(topics = "telemetry.raw.v1", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(TelemetryReceivedEvent event) {
        log.info(
            "Consumed raw telemetry eventId={} deviceId={} metricCount={}",
            event.eventId(),
            event.deviceId(),
            event.metrics().size()
        );
        telemetryEventProcessor.process(event);
    }
}
