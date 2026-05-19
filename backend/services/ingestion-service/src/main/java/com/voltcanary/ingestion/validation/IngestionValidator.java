package com.voltcanary.ingestion.validation;

import com.voltcanary.ingestion.pipeline.IncomingTelemetryMessage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class IngestionValidator {

    private static final Instant MIN_ACCEPTED_TIMESTAMP = Instant.parse("2020-01-01T00:00:00Z");

    public void validate(IncomingTelemetryMessage message) {
        if (message.sourceId() == null || message.sourceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceId is required");
        }
        if (message.timestamp() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timestamp is required");
        }
        Instant nowWithSkew = Instant.now().plus(30, ChronoUnit.SECONDS);
        if (message.timestamp().isBefore(MIN_ACCEPTED_TIMESTAMP) || message.timestamp().isAfter(nowWithSkew)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid telemetry timestamp");
        }
        if (message.metrics() == null || message.metrics().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metrics are required");
        }
        boolean hasNumericMetric = false;
        for (var entry : message.metrics().entrySet()) {
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
