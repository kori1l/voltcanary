package com.voltcanary.telemetry.service;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.telemetry.config.TelemetryValidationProperties;
import com.voltcanary.telemetry.domain.QualityFlag;
import com.voltcanary.telemetry.domain.TelemetryMeasurement;
import com.voltcanary.telemetry.domain.TelemetryQualityFlag;
import com.voltcanary.telemetry.domain.TelemetryReading;
import com.voltcanary.telemetry.producer.TelemetryDeadLetterPublisher;
import com.voltcanary.telemetry.producer.TelemetryValidatedPublisher;
import com.voltcanary.telemetry.repository.TelemetryMeasurementRepository;
import com.voltcanary.telemetry.repository.TelemetryQualityFlagRepository;
import com.voltcanary.telemetry.repository.TelemetryReadingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(TelemetryEventProcessor.class);

    private final TelemetryMeasurementRepository measurementRepository;
    private final TelemetryReadingRepository readingRepository;
    private final TelemetryQualityFlagRepository qualityFlagRepository;
    private final TelemetryValidatedPublisher validatedPublisher;
    private final TelemetryDeadLetterPublisher deadLetterPublisher;
    private final MetricCatalog metricCatalog;
    private final TelemetryValidationProperties validationProperties;
    private final TelemetryIngestionRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public TelemetryEventProcessor(
            TelemetryMeasurementRepository measurementRepository,
            TelemetryReadingRepository readingRepository,
            TelemetryQualityFlagRepository qualityFlagRepository,
            TelemetryValidatedPublisher validatedPublisher,
            TelemetryDeadLetterPublisher deadLetterPublisher,
            MetricCatalog metricCatalog,
            TelemetryValidationProperties validationProperties,
            TelemetryIngestionRateLimiter rateLimiter,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.measurementRepository = measurementRepository;
        this.readingRepository = readingRepository;
        this.qualityFlagRepository = qualityFlagRepository;
        this.validatedPublisher = validatedPublisher;
        this.deadLetterPublisher = deadLetterPublisher;
        this.metricCatalog = metricCatalog;
        this.validationProperties = validationProperties;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public int process(TelemetryReceivedEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "accepted";
        List<String> qualityFlags = new ArrayList<>();
        UUID readingId = UUID.randomUUID();
        int inserted = 0;
        try {
            increment("consumed", "received");
            validateRequiredFields(event, qualityFlags);
            persistReading(event, readingId);
            if (!qualityFlags.isEmpty()) {
                persistQualityFlags(readingId, event.deviceId(), qualityFlags);
                deadLetterPublisher.publish(event, "required_fields_invalid", qualityFlags);
                increment("invalid", "required_fields_invalid");
                outcome = "invalid";
                return 0;
            }

            validateEnvelope(event, qualityFlags);
            if (!rateLimiter.allow(event.deviceId())) {
                addQualityFlag(qualityFlags, QualityFlag.RATE_LIMITED);
                persistQualityFlags(readingId, event.deviceId(), qualityFlags);
                deadLetterPublisher.publish(event, "device_rate_limit_exceeded", qualityFlags);
                increment("invalid", "device_rate_limit_exceeded");
                outcome = "invalid";
                return 0;
            }
            if (measurementRepository.countByDeviceSourceTimestamp(event.deviceId(), event.sourceId(), event.timestamp()) > 0) {
                addQualityFlag(qualityFlags, QualityFlag.DUPLICATE);
                increment("duplicate", "device_source_timestamp");
            }
            measurementRepository.latestTimestamp(event.deviceId())
                    .filter(latestTimestamp -> event.timestamp().isBefore(latestTimestamp))
                    .ifPresent(ignored -> addQualityFlag(qualityFlags, QualityFlag.OUT_OF_ORDER));

            List<TelemetryMeasurement> measurements = normalize(event, qualityFlags);
            persistQualityFlags(readingId, event.deviceId(), qualityFlags);
            if (measurements.isEmpty() || hasRejectingEnvelopeQuality(qualityFlags)) {
                deadLetterPublisher.publish(event, "telemetry_validation_failed", qualityFlags);
                increment("invalid", "telemetry_validation_failed");
                outcome = "invalid";
                return 0;
            }
            inserted = measurementRepository.insertAll(measurements);
            meterRegistry.counter("voltcanary.telemetry.rows", "outcome", "inserted")
                    .increment(inserted);
            if (inserted > 0 && !qualityFlags.contains(QualityFlag.BACKFILL.name())) {
                validatedPublisher.publish(new TelemetryValidatedEvent(
                        UUID.randomUUID(),
                        event.deviceId(),
                        event.sourceId(),
                        event.timestamp(),
                        numericMetrics(event.metrics()),
                        qualityFlags.isEmpty() ? List.of(QualityFlag.OK.name()) : qualityFlags,
                        Instant.now()));
            } else {
                outcome = "duplicate";
            }
            log.info(
                    "Processed telemetry eventId={} deviceId={} metricRows={} insertedRows={} qualityFlags={}",
                    event.eventId(),
                    event.deviceId(),
                    measurements.size(),
                    inserted,
                    qualityFlags);
            return inserted;
        } finally {
            sample.stop(Timer.builder("voltcanary.telemetry.processing")
                    .description("Telemetry event processing latency")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    private List<TelemetryMeasurement> normalize(TelemetryReceivedEvent event, List<String> qualityFlags) {
        Instant createdAt = Instant.now();
        Instant receivedAt = event.receivedAt() == null ? createdAt : event.receivedAt();
        List<TelemetryMeasurement> measurements = new ArrayList<>();
        for (var entry : event.metrics().entrySet()) {
            String metricName = entry.getKey();
            MetricCatalog.MetricDefinition definition = metricCatalog.find(metricName).orElse(null);
            if (definition == null) {
                addQualityFlag(qualityFlags, QualityFlag.INVALID_VALUE);
                increment("invalid_metric", "unsupported_metric");
                continue;
            }
            if (!(entry.getValue() instanceof Number number)) {
                addQualityFlag(qualityFlags, QualityFlag.INVALID_VALUE);
                increment("invalid_metric", "non_numeric");
                continue;
            }
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                addQualityFlag(qualityFlags, QualityFlag.INVALID_VALUE);
                increment("invalid_metric", "non_finite");
                continue;
            }
            if (!definition.accepts(value)) {
                addQualityFlag(qualityFlags, QualityFlag.OUT_OF_RANGE);
                increment("invalid_metric", "out_of_range");
                continue;
            }
            measurements.add(new TelemetryMeasurement(
                    event.deviceId(),
                    event.sourceId(),
                    event.timestamp(),
                    metricName,
                    value,
                    definition.unit(),
                    QualityFlag.OK,
                    receivedAt,
                    createdAt));
        }
        return measurements;
    }

    private Map<String, Object> numericMetrics(Map<String, Object> metrics) {
        Map<String, Object> numeric = new LinkedHashMap<>();
        for (var entry : metrics.entrySet()) {
            if (entry.getValue() instanceof Number && metricCatalog.isSupported(entry.getKey())) {
                numeric.put(entry.getKey(), entry.getValue());
            }
        }
        return numeric;
    }

    private void validateRequiredFields(TelemetryReceivedEvent event, List<String> qualityFlags) {
        if (event == null
                || event.deviceId() == null
                || event.sourceId() == null
                || event.sourceId().isBlank()
                || event.timestamp() == null
                || event.metrics() == null
                || event.metrics().isEmpty()) {
            addQualityFlag(qualityFlags, QualityFlag.MISSING_REQUIRED_FIELD);
        }
    }

    private void validateEnvelope(TelemetryReceivedEvent event, List<String> qualityFlags) {
        Instant now = Instant.now();
        if (event.timestamp().isAfter(now.plus(validationProperties.getMaxFutureSkew()))) {
            addQualityFlag(qualityFlags, QualityFlag.INVALID_TIMESTAMP);
        }
        if (event.timestamp().isBefore(now.minus(validationProperties.getMaxPastAge()))) {
            if (isBackfillSource(event.sourceId())) {
                addQualityFlag(qualityFlags, QualityFlag.BACKFILL);
            } else {
                addQualityFlag(qualityFlags, QualityFlag.INVALID_TIMESTAMP);
            }
        }
        if (event.metrics().size() > validationProperties.getMaxMetricsPerEvent()) {
            addQualityFlag(qualityFlags, QualityFlag.INVALID_VALUE);
        }
    }

    private boolean isBackfillSource(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return false;
        }
        return validationProperties.getBackfillSourcePrefixes().stream()
                .anyMatch(sourceId::startsWith);
    }

    private void persistReading(TelemetryReceivedEvent event, UUID readingId) {
        if (event == null || event.deviceId() == null || event.timestamp() == null) {
            return;
        }
        Instant receivedAt = event.receivedAt() == null ? Instant.now() : event.receivedAt();
        readingRepository.save(new TelemetryReading(
                readingId,
                event.deviceId(),
                event.sourceId(),
                event.timestamp(),
                receivedAt,
                toJson(sanitizedMetrics(event.metrics()))));
    }

    private void persistQualityFlags(UUID readingId, UUID deviceId, List<String> qualityFlags) {
        if (deviceId == null || qualityFlags.isEmpty()) {
            return;
        }
        Instant createdAt = Instant.now();
        qualityFlags.stream()
                .distinct()
                .map(flag -> new TelemetryQualityFlag(
                        UUID.randomUUID(),
                        readingId,
                        deviceId,
                        QualityFlag.valueOf(flag),
                        messageFor(flag),
                        createdAt))
                .forEach(qualityFlagRepository::save);
    }

    private boolean hasRejectingEnvelopeQuality(List<String> qualityFlags) {
        return qualityFlags.contains(QualityFlag.MISSING_REQUIRED_FIELD.name())
                || qualityFlags.contains(QualityFlag.INVALID_TIMESTAMP.name());
    }

    private void addQualityFlag(List<String> qualityFlags, QualityFlag flag) {
        if (!qualityFlags.contains(flag.name())) {
            qualityFlags.add(flag.name());
        }
    }

    private void increment(String metric, String outcome) {
        meterRegistry.counter("voltcanary.telemetry.events", "metric", metric, "outcome", outcome).increment();
    }

    private String toJson(Map<String, Object> metrics) {
        try {
            return objectMapper.writeValueAsString(metrics == null ? Map.of() : metrics);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private String messageFor(String flag) {
        return switch (QualityFlag.valueOf(flag)) {
            case MISSING_REQUIRED_FIELD -> "Telemetry event is missing deviceId, sourceId, timestamp, or metrics.";
            case INVALID_TIMESTAMP -> "Telemetry timestamp is outside the accepted ingestion window.";
            case INVALID_VALUE -> "Telemetry event contains unsupported or non-finite metric values.";
            case OUT_OF_RANGE -> "Telemetry event contains metric values outside the configured range.";
            case DUPLICATE -> "Telemetry event duplicates an already persisted device/source/timestamp.";
            case BACKFILL -> "Historical telemetry import accepted without realtime validated-event publishing.";
            case RATE_LIMITED -> "Telemetry event exceeded the per-device processing rate limit.";
            default -> "Telemetry quality flag recorded.";
        };
    }

    private Map<String, Object> sanitizedMetrics(Map<String, Object> metrics) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (metrics == null) {
            return sanitized;
        }
        for (var entry : metrics.entrySet()) {
            if (metricCatalog.isSupported(entry.getKey())) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized;
    }
}
