package com.voltcanary.ingestion.service;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;
import com.voltcanary.ingestion.kafka.TelemetryEventPublisher;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final TelemetryEventPublisher telemetryEventPublisher;
    private final boolean kafkaPublishEnabled;

    public IngestionService(
            TelemetryEventPublisher telemetryEventPublisher,
            @Value("${app.kafka.publish-enabled:false}") boolean kafkaPublishEnabled) {
        this.telemetryEventPublisher = telemetryEventPublisher;
        this.kafkaPublishEnabled = kafkaPublishEnabled;
    }

    public void ingest(TelemetryIngestionRequest request) {
        TelemetryReceivedEvent event = toEvent(request);
        log.info(
                "Accepted telemetry reading eventId={} deviceId={} sourceId={} metricCount={}",
                event.eventId(),
                event.deviceId(),
                event.sourceId(),
                event.metrics().size());
        if (kafkaPublishEnabled) {
            telemetryEventPublisher.publish(event);
        }
    }

    public void ingestBatch(Collection<TelemetryIngestionRequest> requests) {
        requests.forEach(this::ingest);
    }

    private TelemetryReceivedEvent toEvent(TelemetryIngestionRequest request) {
        return new TelemetryReceivedEvent(
                UUID.randomUUID(),
                request.deviceId(),
                request.sourceId(),
                request.timestamp(),
                request.metrics(),
                Instant.now());
    }
}
