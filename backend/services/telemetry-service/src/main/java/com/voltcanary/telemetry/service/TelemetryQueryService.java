package com.voltcanary.telemetry.service;

import com.voltcanary.telemetry.domain.TelemetryMeasurement;
import com.voltcanary.telemetry.dto.TelemetryChartPointResponse;
import com.voltcanary.telemetry.dto.TelemetryChartResponse;
import com.voltcanary.telemetry.dto.TelemetryEnvelopeResponse;
import com.voltcanary.telemetry.dto.TelemetryExportResponse;
import com.voltcanary.telemetry.dto.TelemetryLatestResponse;
import com.voltcanary.telemetry.dto.TelemetryMetricResponse;
import com.voltcanary.telemetry.dto.TelemetryQualityResponse;
import com.voltcanary.telemetry.dto.TelemetryReadingResponse;
import com.voltcanary.telemetry.repository.TelemetryEnvelopeRepository;
import com.voltcanary.telemetry.repository.TelemetryMeasurementRepository;
import com.voltcanary.telemetry.repository.TelemetryQualityFlagRepository;
import com.voltcanary.telemetry.repository.TelemetryReadingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TelemetryQueryService {

    private final TelemetryMeasurementRepository measurementRepository;
    private final TelemetryReadingRepository readingRepository;
    private final TelemetryQualityFlagRepository qualityFlagRepository;
    private final MetricCatalog metricCatalog;
    private final TelemetryEnvelopeRepository envelopes;

    public List<TelemetryReadingResponse> getReadings(UUID deviceId) {
        return readingRepository.findTop100ByDeviceIdOrderByRecordedAtDesc(deviceId).stream()
                .map(reading -> new TelemetryReadingResponse(
                        reading.getId(),
                        reading.getDeviceId(),
                        reading.getSourceId(),
                        reading.getRecordedAt(),
                        reading.getReceivedAt(),
                        reading.getMetricsJson()))
                .toList();
    }

    public TelemetryEnvelopeResponse envelope(UUID deviceId, String metric, Instant from, Instant to, int buckets) {
        var evidence = envelopes.read(deviceId, metric, from, to, buckets);
        if ("boolean".equals(measurementRepository.findUnit(deviceId, metric)) && evidence.rawPointCount() <= 1000) {
            var points = measurementRepository.findSeries(deviceId, metric, from, to).stream()
                    .map(value -> new TelemetryChartPointResponse(value.recordedAt(), value.metricValue())).toList();
            return new TelemetryEnvelopeResponse(deviceId, metric, points, evidence.rawPointCount(), "MEASURED_STATES",
                    null, evidence.summary(), evidence.qualityCounts());
        }
        return evidence;
    }

    public Optional<TelemetryReadingResponse> getLatest(UUID deviceId) {
        return readingRepository.findFirstByDeviceIdOrderByRecordedAtDesc(deviceId)
                .map(reading -> new TelemetryReadingResponse(
                        reading.getId(),
                        reading.getDeviceId(),
                        reading.getSourceId(),
                        reading.getRecordedAt(),
                        reading.getReceivedAt(),
                        reading.getMetricsJson()));
    }

    public Optional<TelemetryLatestResponse> latestMeasurements(UUID deviceId) {
        return measurementRepository.latestTimestamp(deviceId)
                .map(timestamp -> {
                    Map<String, Double> readings = new LinkedHashMap<>();
                    measurementRepository.findByDeviceIdAndTimestamp(deviceId, timestamp)
                            .forEach(measurement -> readings.put(measurement.metricName(), measurement.metricValue()));
                    return new TelemetryLatestResponse(deviceId, readings, timestamp);
                });
    }

    public TelemetryChartResponse chart(UUID deviceId, String metric, Instant from, Instant to, String interval) {
        List<TelemetryMeasurement> series = interval == null || interval.isBlank()
                ? measurementRepository.findSeries(deviceId, metric, from, to)
                : aggregateSeries(deviceId, metric, from, to, parseInterval(interval));
        List<TelemetryChartPointResponse> points = series.stream()
                .map(measurement -> new TelemetryChartPointResponse(
                        measurement.recordedAt(),
                        measurement.metricValue()))
                .toList();
        return new TelemetryChartResponse(deviceId, metric, points);
    }

    public TelemetryExportResponse export(
            UUID deviceId,
            String metric,
            Instant from,
            Instant to,
            Instant cursor,
            String cursorSourceId,
            int limit) {
        List<TelemetryMeasurement> page = measurementRepository.findSeriesPage(
                deviceId,
                metric,
                from,
                to,
                cursor,
                cursorSourceId,
                limit + 1);
        List<TelemetryMeasurement> visible = page.size() > limit ? page.subList(0, limit) : page;
        List<TelemetryChartPointResponse> points = visible.stream()
                .map(measurement -> new TelemetryChartPointResponse(
                        measurement.recordedAt(),
                        measurement.metricValue(), measurement.sourceId(), measurement.quality().name()))
                .toList();
        String nextCursor = page.size() > limit
                ? visible.getLast().recordedAt().toString()
                : null;
        return new TelemetryExportResponse(deviceId, metric, points, nextCursor,
                page.size() > limit ? visible.getLast().sourceId() : null);
    }

    public List<TelemetryQualityResponse> getQuality(UUID deviceId) {
        return qualityFlagRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId).stream()
                .map(flag -> new TelemetryQualityResponse(
                        flag.getId(),
                        flag.getReadingId(),
                        flag.getDeviceId(),
                        flag.getFlag().name(),
                        flag.getMessage(),
                        flag.getCreatedAt()))
                .toList();
    }

    public List<TelemetryMetricResponse> getMetrics(UUID deviceId) {
        return getMetrics(deviceId, null, null);
    }

    public List<TelemetryMetricResponse> getMetrics(UUID deviceId, Instant from, Instant to) {
        List<String> presentMetrics = from == null ? measurementRepository.findMetricNamesByDeviceId(deviceId)
                : measurementRepository.findMetricNamesByDeviceIdAndRange(deviceId, from, to);
        return presentMetrics.stream().sorted().map(key -> {
            var definition = metricCatalog.find(key).orElseGet(() -> new MetricCatalog.MetricDefinition(measurementRepository.findUnit(deviceId, key), -1e12, 1e12));
            boolean state = "boolean".equals(definition.unit());
            return new TelemetryMetricResponse(key, definition.unit(), state ? "boolean" : definition.type(),
                    state ? 0 : definition.min(), state ? 1 : definition.max(), displayName(key));
        }).toList();
    }

    private List<TelemetryMeasurement> aggregateSeries(
            UUID deviceId,
            String metric,
            Instant from,
            Instant to,
            Duration interval) {
        long bucketMillis = Math.max(1, interval.toMillis());
        Map<Long, List<TelemetryMeasurement>> buckets = new LinkedHashMap<>();
        for (TelemetryMeasurement measurement : measurementRepository.findSeriesRaw(deviceId, metric, from, to)) {
            long bucket = measurement.recordedAt().toEpochMilli() / bucketMillis * bucketMillis;
            buckets.computeIfAbsent(bucket, ignored -> new java.util.ArrayList<>()).add(measurement);
        }
        return buckets.entrySet().stream()
                .map(entry -> {
                    List<TelemetryMeasurement> bucket = entry.getValue();
                    TelemetryMeasurement first = bucket.getFirst();
                    double average = bucket.stream().mapToDouble(TelemetryMeasurement::metricValue).average().orElse(0);
                    return new TelemetryMeasurement(
                            first.deviceId(),
                            first.sourceId(),
                            Instant.ofEpochMilli(entry.getKey()),
                            first.metricName(),
                            average,
                            first.unit(),
                            first.quality(),
                            first.receivedAt(),
                            first.createdAt());
                })
                .toList();
    }

    private Duration parseInterval(String interval) {
        if (interval.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(interval.substring(0, interval.length() - 1)));
        }
        if (interval.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(interval.substring(0, interval.length() - 1)));
        }
        if (interval.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(interval.substring(0, interval.length() - 1)));
        }
        return Duration.parse(interval);
    }

    private String displayName(String metricName) {
        return switch (metricName) {
            case "activePowerW" -> "Active power";
            case "voltageV" -> "Voltage";
            case "currentA" -> "Current";
            case "energyWh" -> "Energy";
            case "rssi" -> "Signal strength";
            case "temperatureC" -> "Temperature";
            case "vibrationMmS" -> "Vibration";
            case "runtimeHours" -> "Runtime";
            case "powerFactor" -> "Power factor";
            case "frequencyHz" -> "Frequency";
            default -> metricName;
        };
    }
}
