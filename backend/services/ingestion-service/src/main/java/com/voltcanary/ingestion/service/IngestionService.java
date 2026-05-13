package com.voltcanary.ingestion.service;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.device.DeviceValidationClient;
import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;
import com.voltcanary.ingestion.idempotency.IdempotencyService;
import com.voltcanary.ingestion.kafka.TelemetryEventPublisher;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private static final Instant MIN_ACCEPTED_TIMESTAMP = Instant.parse("2020-01-01T00:00:00Z");

    private final TelemetryEventPublisher telemetryEventPublisher;
    private final DeviceValidationClient deviceValidationClient;
    private final IdempotencyService idempotencyService;
    private final boolean kafkaPublishEnabled;

    public IngestionService(
            TelemetryEventPublisher telemetryEventPublisher,
            DeviceValidationClient deviceValidationClient,
            IdempotencyService idempotencyService,
            @Value("${app.kafka.publish-enabled:false}") boolean kafkaPublishEnabled) {
        this.telemetryEventPublisher = telemetryEventPublisher;
        this.deviceValidationClient = deviceValidationClient;
        this.idempotencyService = idempotencyService;
        this.kafkaPublishEnabled = kafkaPublishEnabled;
    }

    public void ingest(TelemetryIngestionRequest request) {
        validate(request);
        if (!deviceValidationClient.dataSourceExists(request.deviceId(), request.sourceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown device data source");
        }
        if (!idempotencyService.markIfNew(request.deviceId(), request.sourceId(), request.timestamp())) {
            log.info(
                    "Ignoring duplicate telemetry reading deviceId={} sourceId={} timestamp={}",
                    request.deviceId(),
                    request.sourceId(),
                    request.timestamp());
            return;
        }

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

    private void validate(TelemetryIngestionRequest request) {
        Instant nowWithSkew = Instant.now().plus(30, ChronoUnit.SECONDS);
        if (request.timestamp().isBefore(MIN_ACCEPTED_TIMESTAMP) || request.timestamp().isAfter(nowWithSkew)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid telemetry timestamp");
        }
        boolean hasNumericMetric = false;
        for (var entry : request.metrics().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Metric name must not be blank");
            }
            Object value = entry.getValue();
            if (value instanceof Number number) {
                double doubleValue = number.doubleValue();
                if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Metric value must be finite");
                }
                hasNumericMetric = true;
            } else if (!"relayState".equals(entry.getKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Metric value must be numeric");
            }
        }
        if (!hasNumericMetric) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one numeric metric is required");
        }
    }
}
