package com.voltcanary.analytics.producer;

import com.voltcanary.analytics.config.AnalyticsKafkaProperties;
import com.voltcanary.analytics.domain.AnalyticsEventOutbox;
import com.voltcanary.analytics.repository.AnalyticsEventOutboxRepository;
import java.time.Instant;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.kafka.publish-enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsOutboxPublisher.class);

    private final AnalyticsEventOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final boolean publishEnabled;
    private final MeterRegistry meterRegistry;

    public AnalyticsOutboxPublisher(
            AnalyticsEventOutboxRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            AnalyticsKafkaProperties kafkaProperties,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.publishEnabled = kafkaProperties.isPublishEnabled();
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.analytics.outbox.publish-delay:5s}")
    @Transactional
    public void publishPending() {
        if (!publishEnabled) {
            return;
        }
        for (AnalyticsEventOutbox event : repository.findTop100ByStatusOrderByCreatedAtAsc("PENDING")) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayloadJson()).get();
                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
                meterRegistry.counter("analytics.events.published", "topic", event.getTopic(), "status", "published").increment();
            } catch (Exception exception) {
                log.warn("analyticsOutboxPublishFailed outboxId={} topic={} reasonCode={}",
                        event.getId(),
                        event.getTopic(),
                        exception.getClass().getSimpleName());
                event.setAttempts(event.getAttempts() + 1);
                event.setLastError(message(exception));
                if (event.getAttempts() >= 5) {
                    event.setStatus("FAILED");
                }
                meterRegistry.counter("analytics.events.published", "topic", event.getTopic(), "status", "failed").increment();
            }
        }
    }

    private String message(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
