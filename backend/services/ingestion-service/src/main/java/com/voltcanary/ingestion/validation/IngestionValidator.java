package com.voltcanary.ingestion.validation;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.config.IngestionProperties;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class IngestionValidator {

    private final IngestionProperties properties;
    private final IngestionMetricCatalog metricCatalog;

    public void validate(IncomingTelemetryMessage message) {
        validate(message, false);
    }

    public void validate(IncomingTelemetryMessage message, boolean allowMissingConfiguredInputs) {
        validateEnvelope(message);
        validateMetrics(message, allowMissingConfiguredInputs);
    }

    public void validateEnvelope(IncomingTelemetryMessage message) {
        if (message.sourceId() == null || message.sourceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceId is required");
        }
        if (message.sourceId().length() > properties.getMaxSourceIdLength()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "sourceId exceeds configured length limit"
            );
        }
        if (message.occurredAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timestamp is required");
        }
        Instant now = Instant.now();
        Instant nowWithSkew = now.plus(properties.getMaxFutureSkew());
        Instant oldestAccepted = now.minus(properties.getMaxBackfillAge());
        if (
            message.occurredAt().isBefore(properties.getMinAcceptedTimestamp()) ||
            (!message.historicalImport() && message.occurredAt().isBefore(oldestAccepted)) ||
            message.occurredAt().isAfter(nowWithSkew)
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid telemetry timestamp"
            );
        }
        if (message.metrics() == null || message.metrics().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metrics are required");
        }
        if (message.metrics().size() > properties.getMaxMetricsPerReading()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Too many metrics in telemetry reading"
            );
        }
    }

    private void validateMetrics(
        IncomingTelemetryMessage message,
        boolean allowMissingConfiguredInputs
    ) {
        if (!com.voltcanary.common.events.telemetry.GpsMetrics.valid(message.metrics())) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "GPS requires a valid coordinate pair, speed and course"
            );
        }
        boolean hasNumericMetric = !com.voltcanary.common.events.telemetry.ConfiguredFields.values(
            message.metrics()
        ).isEmpty();
        for (var entry : message.metrics().entrySet()) {
            if (
                "transport".equals(entry.getKey()) || "configuredFields".equals(entry.getKey())
            ) continue; // bounded context validated with GPS above
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Metric name must not be blank"
                );
            }
            if (entry.getKey().length() > properties.getMaxMetricNameLength()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Metric name exceeds configured length limit"
                );
            }
            Object value = entry.getValue();
            if (value instanceof Number number) {
                IngestionMetricCatalog.MetricDefinition definition = metricCatalog
                    .find(entry.getKey())
                    .orElseThrow(() ->
                        new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Unsupported metric name"
                        )
                    );
                double doubleValue = number.doubleValue();
                if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Metric value must be finite"
                    );
                }
                if (!definition.accepts(doubleValue)) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Metric value is outside supported range"
                    );
                }
                hasNumericMetric = true;
            } else if (!"relayState".equals(entry.getKey())) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Metric value must be numeric"
                );
            }
        }
        if (!hasNumericMetric && !allowMissingConfiguredInputs) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "At least one numeric metric is required"
            );
        }
    }
}
