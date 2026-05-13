package com.voltcanary.telemetry.service;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import com.voltcanary.telemetry.domain.QualityFlag;
import com.voltcanary.telemetry.domain.TelemetryMeasurement;
import com.voltcanary.telemetry.producer.TelemetryValidatedPublisher;
import com.voltcanary.telemetry.repository.TelemetryMeasurementRepository;
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
    private final TelemetryValidatedPublisher validatedPublisher;

    public TelemetryEventProcessor(
            TelemetryMeasurementRepository measurementRepository,
            TelemetryValidatedPublisher validatedPublisher) {
        this.measurementRepository = measurementRepository;
        this.validatedPublisher = validatedPublisher;
    }

    @Transactional
    public int process(TelemetryReceivedEvent event) {
        List<String> qualityFlags = new ArrayList<>();
        if (measurementRepository.countByDeviceSourceTimestamp(event.deviceId(), event.sourceId(), event.timestamp()) > 0) {
            qualityFlags.add(QualityFlag.DUPLICATE.name());
        }

        List<TelemetryMeasurement> measurements = normalize(event, qualityFlags);
        int inserted = measurementRepository.insertAll(measurements);
        if (inserted > 0) {
            validatedPublisher.publish(new TelemetryValidatedEvent(
                    UUID.randomUUID(),
                    event.deviceId(),
                    event.sourceId(),
                    event.timestamp(),
                    numericMetrics(event.metrics()),
                    qualityFlags.isEmpty() ? List.of(QualityFlag.OK.name()) : qualityFlags,
                    Instant.now()));
        }
        log.info(
                "Processed telemetry eventId={} deviceId={} metricRows={} insertedRows={}",
                event.eventId(),
                event.deviceId(),
                measurements.size(),
                inserted);
        return inserted;
    }

    private List<TelemetryMeasurement> normalize(TelemetryReceivedEvent event, List<String> qualityFlags) {
        Instant createdAt = Instant.now();
        List<TelemetryMeasurement> measurements = new ArrayList<>();
        for (var entry : event.metrics().entrySet()) {
            if (!(entry.getValue() instanceof Number number)) {
                continue;
            }
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                qualityFlags.add(QualityFlag.INVALID_VALUE.name());
                continue;
            }
            measurements.add(new TelemetryMeasurement(
                    event.deviceId(),
                    event.sourceId(),
                    event.timestamp(),
                    entry.getKey(),
                    value,
                    unitFor(entry.getKey()),
                    QualityFlag.OK,
                    createdAt));
        }
        return measurements;
    }

    private Map<String, Object> numericMetrics(Map<String, Object> metrics) {
        Map<String, Object> numeric = new LinkedHashMap<>();
        for (var entry : metrics.entrySet()) {
            if (entry.getValue() instanceof Number) {
                numeric.put(entry.getKey(), entry.getValue());
            }
        }
        return numeric;
    }

    private String unitFor(String metricName) {
        return switch (metricName) {
            case "activePowerW" -> "W";
            case "voltageV" -> "V";
            case "currentA" -> "A";
            case "energyWh" -> "Wh";
            case "rssi" -> "dBm";
            default -> null;
        };
    }
}
