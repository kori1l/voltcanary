package com.voltcanary.ingestion.producer;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import com.voltcanary.ingestion.source.SourceRuntimeConfigConsumer;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.ingestion.horizontal-scale-smoke",
    name = "enabled",
    havingValue = "true"
)
public class SmokeRuntimeConfigPublisher {

    private final KafkaTemplate<String, SourceRuntimeConfigEvent> kafka;

    public void publish(SourceRuntimeConfigEvent event) {
        try {
            kafka
                .send(SourceRuntimeConfigConsumer.TOPIC, event.dataSourceId().toString(), event)
                .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Source runtime publication interrupted", interrupted);
        } catch (Exception unavailable) {
            throw new IllegalStateException(
                "Source runtime publication was not confirmed",
                unavailable
            );
        }
    }
}
